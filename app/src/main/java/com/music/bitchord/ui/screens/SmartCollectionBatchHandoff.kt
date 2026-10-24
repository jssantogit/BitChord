package com.music.bitchord.ui.screens

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.bundleOf
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.MoreExecutors
import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.catalog.OpenSubsonicLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.provider.opensubsonic.AndroidKeystoreOpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.listening.AdaptiveCollectionKind
import com.music.bitchord.data.listening.CanonicalRuleBasedSmartPlaylistPreview
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RoomListeningEventStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.data.listening.RuleBasedSmartPlaylistQuery
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics
import java.util.UUID
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistDefinitionRepository
import com.music.bitchord.playback.ACTION_EXECUTE_CANONICAL_BATCH
import com.music.bitchord.playback.EXTRA_CANONICAL_BATCH_TOKEN
import com.music.bitchord.playback.CanonicalBatchQueueRequests
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlan
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.playback.androidCanonicalQueueResolver
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.smart_collection_batch_added
import com.music.bitchord.sharedui.resources.smart_collection_batch_failed
import com.music.bitchord.sharedui.resources.smart_collection_batch_empty
import com.music.bitchord.sharedui.resources.smart_collection_batch_played
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.resume

/** Deliberate click target: neither playlist name nor provider key is a RecordingId. */
internal sealed interface SmartCollectionActionSource {
    data class Adaptive(val kind: AdaptiveCollectionKind) : SmartCollectionActionSource
    data class SavedDefinition(val id: String) : SmartCollectionActionSource
    data class PreviewQuery(val query: RuleBasedSmartPlaylistQuery) : SmartCollectionActionSource
}

internal sealed interface SmartCollectionBatchResult {
    data object Submitted : SmartCollectionBatchResult
    data object Unavailable : SmartCollectionBatchResult
    data object Empty : SmartCollectionBatchResult
    data object LimitExceeded : SmartCollectionBatchResult
    data class Rejected(val count: Int) : SmartCollectionBatchResult
    data object ExecutionFailed : SmartCollectionBatchResult
}

/**
 * Listening-owned revalidation and B9 planning only; B10 is the sole queue
 * mutator. UI clicks never manufacture Song, provider URLs or playback events.
 * Missing/ambiguous/partial selections fail CLOSED; one click, one batch token.
 */
internal class SmartCollectionBatchHandoff(
    private val refreshIds: suspend (SmartCollectionActionSource) -> List<String>?,
    private val browse: suspend () -> CanonicalBrowseLibrary,
    private val planner: CanonicalCollectionIntentPlanner,
    private val submit: suspend (CanonicalCollectionIntentPlan) -> Boolean,
    private val trace: (Map<String, String>) -> Unit = {},
) {
    suspend fun execute(
        source: SmartCollectionActionSource,
        intent: CanonicalCollectionIntent,
    ): SmartCollectionBatchResult {
        // Process-ephemeral correlation, never an account/Recording/MediaAsset identifier.
        val attempt = UUID.randomUUID().toString().substring(0, 8)
        val base = mapOf(
            "attempt" to attempt,
            "intent" to intent.name,
            "source" to when (source) {
                is SmartCollectionActionSource.Adaptive -> "adaptive"
                is SmartCollectionActionSource.SavedDefinition -> "saved"
                is SmartCollectionActionSource.PreviewQuery -> "preview"
            },
        )
        fun mark(stage: String, extra: Map<String, String> = emptyMap()) {
            // A telemetry problem must never change user-intent semantics.
            runCatching { trace(base + mapOf("stage" to stage) + extra) }
        }
        mark("intent")
        try {
            val selected = refreshIds(source)
            if (selected == null) {
                mark("revalidated", mapOf("reason" to "definition_or_source_unavailable"))
                return SmartCollectionBatchResult.Unavailable
            }
            mark("revalidated", mapOf("candidateCount" to selected.size.toString()))
            if (selected.isEmpty()) {
                mark("rejected", mapOf("reason" to "ids_zero"))
                return SmartCollectionBatchResult.Empty
            }
            if (selected.size > CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE) {
                mark("rejected", mapOf("reason" to "limit_exceeded"))
                return SmartCollectionBatchResult.LimitExceeded
            }
            val ids = selected.distinct()
            val library = browse()
            mark("catalog", mapOf("trackCount" to library.tracks.size.toString()))
            val plan = planner.preview(
                ids, intent, library, preferSingleLocalCopy = true,
            )
            val reasons = plan.rejected.groupingBy { it.reason.name }.eachCount()
                .toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
            mark("planned", mapOf(
                "eligibleCount" to ids.size.toString(),
                "selectionCount" to plan.selections.size.toString(),
                "rejectedCount" to plan.rejected.size.toString(),
                "rejectionCodes" to reasons.ifEmpty { "none" },
            ))
            if (plan.rejected.isNotEmpty() || plan.selections.size != ids.size) {
                mark("rejected", mapOf("reason" to "planning_failed"))
                return SmartCollectionBatchResult.Rejected(plan.rejected.size)
            }
            if (plan.selections.isEmpty()) {
                mark("rejected", mapOf("reason" to "selections_zero"))
                return SmartCollectionBatchResult.Empty
            }
            val sent = submit(plan.copy(traceRef = attempt))
            mark("media3", mapOf("result" to if (sent) "command_accepted" else "command_failed"))
            return if (sent) SmartCollectionBatchResult.Submitted
                else SmartCollectionBatchResult.ExecutionFailed
        } catch (cancelled: CancellationException) {
            mark("cancelled")
            throw cancelled
        } catch (_: Exception) {
            mark("exception", mapOf("reason" to "execution_exception"))
            return SmartCollectionBatchResult.ExecutionFailed
        }
    }
}

/** Re-evaluate the exact L11/L12 eligibility at click time, never a stale card. */
private suspend fun currentSmartCollectionIds(
    context: Context,
    source: SmartCollectionActionSource,
): List<String>? {
    return when (source) {
    is SmartCollectionActionSource.Adaptive -> {
        val fresh = persistentAdaptiveCollectionsReader(context).load()
            as? AdaptiveCollectionsUiState.Ready ?: return null
        fresh.collections.firstOrNull { it.kind == source.kind }
            ?.rows?.map { it.recordingId }
    }
    is SmartCollectionActionSource.SavedDefinition -> {
        val repo = SmartPlaylistDefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).definitionDao(),
        )
        val definition = repo.list().firstOrNull { it.id == source.id } ?: return null
        currentSmartPlaylistQueryIds(context, definition.query)
    }
    is SmartCollectionActionSource.PreviewQuery -> {
        currentSmartPlaylistQueryIds(context, source.query)
    }
    }
}

private suspend fun currentSmartPlaylistQueryIds(
    context: Context,
    query: RuleBasedSmartPlaylistQuery,
): List<String>? {
    val availability = CanonicalLibraryAvailabilityReaders.persistent(context)
    if (availability.availableRecordingIds().size > 5_000) return null
    return persistentSmartPlaylistPreview(context, availability)
        .preview(query).map { it.recordingId }
}

/**
 * Catalog-only read projection. Provider configuration remains Catalog-owned,
 * using its existing local/credential-backed eligibility; no auth URLs, native
 * source IDs or network calls are passed to Playback or the UI.
 */
private suspend fun currentCanonicalBrowse(context: Context): CanonicalBrowseLibrary {
    val dao = CanonicalCatalogDatabase.get(context).catalogDao()
    val accounts = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(context),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(context),
    )
    val providers = OpenSubsonicLibraryProviderConfigurationReader(accounts)
        .configuredProviderKeys().toList().sorted()
    if (providers.isEmpty()) return CanonicalBrowseLibrary.EMPTY
    val rows = dao.observeMultiSourceBrowseAssets(providers).first()
    val tracks = rows.groupBy { it.recordingId }.values.mapNotNull { group ->
        val first = group.firstOrNull() ?: return@mapNotNull null
        CanonicalBrowseTrack(
            recordingId = first.recordingId,
            title = first.title,
            artistDisplay = first.artistDisplay,
            durationMs = first.durationMs,
            artworkUri = first.artworkUri,
            availableAssets = group.map {
                CanonicalBrowseMediaAsset(it.mediaAssetId, it.providerKey)
            }.distinct().sortedWith(
                compareBy(CanonicalBrowseMediaAsset::providerKey)
                    .thenBy(CanonicalBrowseMediaAsset::mediaAssetId),
            ),
        )
    }
    return CanonicalBrowseLibrary(tracks)
}

/** Actual Media3 result is awaited; command rejection is never shown as queued. */
private suspend fun submitCanonicalBatch(
    controller: MediaController?,
    plan: CanonicalCollectionIntentPlan,
): Boolean {
    val session = controller ?: return false
    val token = CanonicalBatchQueueRequests.submit(plan)
    val request = try {
        session.sendCustomCommand(
            SessionCommand(ACTION_EXECUTE_CANONICAL_BATCH, Bundle.EMPTY),
            bundleOf(EXTRA_CANONICAL_BATCH_TOKEN to token),
        )
    } catch (cancelled: CancellationException) {
        CanonicalBatchQueueRequests.take(token)
        throw cancelled
    } catch (_: Exception) {
        CanonicalBatchQueueRequests.take(token)
        return false
    }
    return try {
        suspendCancellableCoroutine { continuation ->
            request.addListener({
                val result = runCatching {
                    request.get().resultCode == SessionResult.RESULT_SUCCESS
                }.getOrDefault(false)
                if (continuation.isActive) continuation.resume(result)
            }, MoreExecutors.directExecutor())
        }
    } catch (cancelled: CancellationException) {
        // Once dispatched, service may already have taken the one-shot token.
        CanonicalBatchQueueRequests.take(token)
        throw cancelled
    } catch (_: Exception) {
        CanonicalBatchQueueRequests.take(token)
        false
    }
}

/** Serialized user intent; duplicate rapid taps cannot submit twice. */
@Composable
internal fun rememberSmartCollectionBatchAction(
    controller: MediaController?,
): (SmartCollectionActionSource, CanonicalCollectionIntent) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val working = remember { mutableStateOf(false) }
    val handoff = remember(context, controller) {
        SmartCollectionBatchHandoff(
            refreshIds = { source -> currentSmartCollectionIds(context, source) },
            browse = { currentCanonicalBrowse(context) },
            planner = CanonicalCollectionIntentPlanner(androidCanonicalQueueResolver(context)),
            submit = { plan -> submitCanonicalBatch(controller, plan) },
            trace = { data ->
                Diagnostics.event(
                    category = DiagnosticCategory.PLAYER,
                    name = "smart_collection_action_v1",
                    metadata = data,
                )
            },
        )
    }
    val added = stringResource(Res.string.smart_collection_batch_added)
    val played = stringResource(Res.string.smart_collection_batch_played)
    val empty = stringResource(Res.string.smart_collection_batch_empty)
    val failed = stringResource(Res.string.smart_collection_batch_failed)
    return { source, intent ->
        if (!working.value) {
            working.value = true
            scope.launch {
                val outcome = try {
                    withContext(Dispatchers.IO) { handoff.execute(source, intent) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SmartCollectionBatchResult.ExecutionFailed
                } finally {
                    working.value = false
                }
                val message = when (outcome) {
                    SmartCollectionBatchResult.Submitted ->
                        if (intent == CanonicalCollectionIntent.PLAY_NOW) played else added
                    SmartCollectionBatchResult.Empty -> empty
                    else -> failed
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
