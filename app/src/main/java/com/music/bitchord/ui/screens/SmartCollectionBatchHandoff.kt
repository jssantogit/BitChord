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
) {
    suspend fun execute(
        source: SmartCollectionActionSource,
        intent: CanonicalCollectionIntent,
    ): SmartCollectionBatchResult {
        val selected = refreshIds(source) ?: return SmartCollectionBatchResult.Unavailable
        if (selected.isEmpty()) return SmartCollectionBatchResult.Empty
        if (selected.size > CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE) {
            return SmartCollectionBatchResult.LimitExceeded
        }
        val ids = selected.distinct() // canonical Recording dedup, original order kept
        val plan = planner.preview(ids, intent, browse())
        if (plan.rejected.isNotEmpty() || plan.selections.size != ids.size) {
            return SmartCollectionBatchResult.Rejected(plan.rejected.size)
        }
        if (plan.selections.isEmpty()) return SmartCollectionBatchResult.Empty
        return if (submit(plan)) SmartCollectionBatchResult.Submitted
        else SmartCollectionBatchResult.ExecutionFailed
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
        val availability = CanonicalLibraryAvailabilityReaders.persistent(context)
        // L6 cannot silently truncate an oversized candidate population.
        if (availability.availableRecordingIds().size > 5_000) return null
        persistentSmartPlaylistPreview(context, availability)
            .preview(definition.query).map { it.recordingId }
    }
    }
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
