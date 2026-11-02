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
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRemoteRatingReader
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingConsumerSnapshot
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.listening.AdaptiveCollectionKind
import com.music.bitchord.data.listening.CanonicalRuleBasedSmartPlaylistPreview
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RoomListeningEventStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.data.listening.RuleBasedSmartPlaylistQuery
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
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
import com.music.bitchord.playback.RoomCanonicalQueueResolver
import com.music.bitchord.playback.SmartCollectionPlaybackTrace
import com.music.bitchord.playback.androidCanonicalQueueResolver
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.smart_collection_batch_added
import com.music.bitchord.sharedui.resources.smart_collection_batch_failed
import com.music.bitchord.sharedui.resources.smart_collection_batch_empty
import com.music.bitchord.sharedui.resources.smart_collection_batch_played
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    data class SavedV2Definition(val id: String, val shuffle: Boolean = false) : SmartCollectionActionSource
    /** A recent-addition card refers to one canonical recording, never a URL or title. */
    data class RecentLocalTrack(val recordingId: String) : SmartCollectionActionSource
    /** Library row: optionally restrict playback to a selected source/account. */
    data class LibraryTrack(val recordingId: String, val sourceKey: String?) : SmartCollectionActionSource
    /** Native unified Library Play/Shuffle: snapshot of visible canonical IDs, not fake Song IDs. */
    data class LibraryTracks(
        val recordingIds: List<String>,
        val sourceKey: String?,
        val shuffle: Boolean,
    ) : SmartCollectionActionSource
    data class SavedStaticPlaylist(val id: String, val shuffle: Boolean = false) : SmartCollectionActionSource
    /** Click a real row: verify current saved membership before playing its ordered suffix. */
    data class SavedStaticPlaylistFromTrack(
        val id: String, val recordingId: String, val includeFollowing: Boolean = true,
    ) : SmartCollectionActionSource
    data class SavedV2PlaylistFromTrack(
        val id: String, val recordingId: String, val includeFollowing: Boolean = true,
    ) : SmartCollectionActionSource
    data class PreviewV2Query(val query: SmartPlaylistV2Query, val ownerId: String? = null) : SmartCollectionActionSource
}

/** Null means the clicked track is no longer a member of the saved playlist. */
internal fun playlistPlaybackSuffix(
    currentRecordingIds: List<String>, clickedRecordingId: String,
): List<String>? {
    val index = currentRecordingIds.indexOf(clickedRecordingId)
    return if (index < 0) null else currentRecordingIds.drop(index)
}

/** A queue menu action adds exactly one verified song, never the following songs. */
internal fun playlistPlaybackSelection(
    ids: List<String>, clickedRecordingId: String, includeFollowing: Boolean,
): List<String>? = if (includeFollowing) {
    playlistPlaybackSuffix(ids, clickedRecordingId)
} else if (clickedRecordingId in ids) {
    listOf(clickedRecordingId)
} else null

/** Shuffle only verified canonical IDs; never provider IDs or stale UI rows. */
internal fun canonicalSavedPlaylistOrder(
    selected: List<String>,
    shuffle: Boolean,
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<String> = selected.distinct().let { if (shuffle) it.shuffled(random) else it }

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
    private val verifiedRemoteAssets: suspend (SmartCollectionActionSource) -> Map<String, CanonicalBrowseMediaAsset> = { emptyMap() },
    /** Production uses the selected-only Catalog path; legacy tests retain browse(). */
    private val browseSelected: suspend (List<String>) -> CanonicalBrowseLibrary = { browse() },
    private val sourceLimit: suspend (SmartCollectionActionSource, CanonicalBrowseLibrary) -> CanonicalBrowseLibrary =
        { _, library -> library },
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
                is SmartCollectionActionSource.RecentLocalTrack -> "recent_local"
                is SmartCollectionActionSource.LibraryTrack -> "library_track"
                is SmartCollectionActionSource.LibraryTracks -> "library_tracks"
                is SmartCollectionActionSource.SavedDefinition -> "saved"
                is SmartCollectionActionSource.PreviewQuery -> "preview"
                is SmartCollectionActionSource.SavedV2Definition -> "saved_v2"
                is SmartCollectionActionSource.SavedStaticPlaylist -> "normal_static"
                is SmartCollectionActionSource.SavedStaticPlaylistFromTrack -> "normal_track"
                is SmartCollectionActionSource.SavedV2PlaylistFromTrack -> "smart_v2_track"
                is SmartCollectionActionSource.PreviewV2Query -> "preview_v2"
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
            if (selected.size > CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE &&
                source !is SmartCollectionActionSource.LibraryTracks) {
                mark("rejected", mapOf("reason" to "limit_exceeded"))
                return SmartCollectionBatchResult.LimitExceeded
            }
            // Shuffle only freshly revalidated membership, not stale UI rows.
            val shuffleRequested = intent == CanonicalCollectionIntent.PLAY_NOW &&
                when (source) {
                    is SmartCollectionActionSource.SavedStaticPlaylist -> source.shuffle
                    is SmartCollectionActionSource.SavedV2Definition -> source.shuffle
                    is SmartCollectionActionSource.LibraryTracks -> source.shuffle
                    else -> false
                }
            val ids = canonicalSavedPlaylistOrder(selected, shuffleRequested)
            // A Library with >500 recordings uses several B10-safe commands:
            // play the first verified chunk, then append the remaining chunks.
            // No individual request may exceed the Media3 service batch policy.
            val chunks = if (source is SmartCollectionActionSource.LibraryTracks)
                ids.chunked(CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE)
                else listOf(ids)
            for ((chunkIndex, chunk) in chunks.withIndex()) {
                val library = sourceLimit(source, browseSelected(chunk))
                mark("catalog", mapOf(
                    "chunk" to (chunkIndex + 1).toString(),
                    "chunkCount" to chunks.size.toString(),
                    "trackCount" to library.tracks.size.toString(),
                ))
                val currentAssets = library.tracks.associate { track ->
                    track.recordingId to track.availableAssets.toSet()
                }
                val scopedRemote = verifiedRemoteAssets(source).filter { (id, hint) ->
                    currentAssets[id]?.any { sameCanonicalAssetIdentity(it, hint) } == true
                }
                mark("asset_evidence", mapOf("uniqueRatedAssets" to scopedRemote.size.toString()))
                val chunkIntent = if (chunkIndex == 0) intent else CanonicalCollectionIntent.ADD_TO_QUEUE
                val plan = planner.preview(
                    chunk, chunkIntent, library,
                    preferSingleLocalCopy = true,
                    verifiedRemoteAssetByRecording = scopedRemote,
                )
                val reasons = plan.rejected.groupingBy { it.reason.name }.eachCount()
                    .toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
                mark("planned", mapOf(
                    "eligibleCount" to chunk.size.toString(),
                    "selectionCount" to plan.selections.size.toString(),
                    "rejectedCount" to plan.rejected.size.toString(),
                    "rejectionCodes" to reasons.ifEmpty { "none" },
                    "chunk" to (chunkIndex + 1).toString(),
                ))
                if (plan.rejected.isNotEmpty() || plan.selections.size != chunk.size) {
                    mark("rejected", mapOf(
                        "reason" to "planning_failed",
                        "completedChunks" to chunkIndex.toString(),
                    ))
                    return SmartCollectionBatchResult.Rejected(plan.rejected.size)
                }
                if (plan.selections.isEmpty()) {
                    mark("rejected", mapOf("reason" to "selections_zero"))
                    return SmartCollectionBatchResult.Empty
                }
                val sent = submit(plan.copy(traceRef = attempt))
                mark("media3", mapOf(
                    "result" to if (sent) "command_accepted" else "command_failed",
                    "chunk" to (chunkIndex + 1).toString(),
                    "chunkCount" to chunks.size.toString(),
                ))
                if (!sent) return SmartCollectionBatchResult.ExecutionFailed
            }
            return SmartCollectionBatchResult.Submitted
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
    is SmartCollectionActionSource.RecentLocalTrack -> listOf(source.recordingId)
    is SmartCollectionActionSource.LibraryTrack -> listOf(source.recordingId)
    is SmartCollectionActionSource.LibraryTracks -> source.recordingIds.distinct()
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
    is SmartCollectionActionSource.SavedV2Definition -> {
        val repo = SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
        val definition = repo.list().firstOrNull { it.id == source.id } ?: return null
        currentSmartPlaylistV2QueryIds(context, definition.query, definition.id)
    }
    is SmartCollectionActionSource.PreviewV2Query -> {
        currentSmartPlaylistV2QueryIds(context, source.query, source.ownerId)
    }
    is SmartCollectionActionSource.SavedStaticPlaylist -> {
        val repo = CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
        repo.list().firstOrNull { it.id == source.id }?.recordingIds
    }
    is SmartCollectionActionSource.SavedStaticPlaylistFromTrack -> {
        val repo = CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
        val saved = repo.list().firstOrNull { it.id == source.id } ?: return null
        playlistPlaybackSelection(saved.recordingIds, source.recordingId, source.includeFollowing)
    }
    is SmartCollectionActionSource.SavedV2PlaylistFromTrack -> {
        val repo = SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
        val saved = repo.list().firstOrNull { it.id == source.id } ?: return null
        playlistPlaybackSelection(
            currentSmartPlaylistV2QueryIds(context, saved.query, saved.id),
            source.recordingId, source.includeFollowing,
        )
    }
    }
}

private suspend fun currentSmartPlaylistQueryIds(
    context: Context,
    query: RuleBasedSmartPlaylistQuery,
): List<String>? {
    val availability = CanonicalLibraryAvailabilityReaders.persistent(context)
    return persistentSmartPlaylistPreview(context, availability)
        .preview(query).map { it.recordingId }
}

private suspend fun currentSmartPlaylistV2QueryIds(
    context: Context,
    query: SmartPlaylistV2Query,
    ownerId: String? = null,
): List<String> = persistentSmartPlaylistPreview(context)
    .preview(query, ownerId).map { it.recordingId }

/**
 * Catalog-owned source evidence only: a uniquely rated MediaAsset can break
 * ties among multiple renditions of the same canonical Recording. This MUST
 * use a complete current-account snapshot. No rating data is persisted locally.
 * A stale or ambiguous remote preference never picks an asset.
 */
/**
 * Prove that every possible match must have a numeric rating >= a threshold.
 * A disjunction with an unrated branch or a negation cannot authorize a
 * remote MediaAsset choice. Missing evidence still fails closed downstream.
 */
internal fun SmartPlaylistV2Query.guaranteedMinimumRatingForAssetHints(): Double? {
    fun proof(node: SmartPlaylistRule): Double? = when (node) {
        is SmartPlaylistRule.Not -> null
        is SmartPlaylistRule.Reference -> null // A nested playlist does not prove a numeric rating threshold.
        is SmartPlaylistRule.Comparison -> {
            if (node.field == SmartPlaylistRuleField.EXPLICIT_RATING &&
                node.operator in setOf(
                    SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                    SmartPlaylistRuleOperator.EQUAL,
                ) &&
                node.value != null && node.value in 1.0..5.0
            ) node.value else null
        }
        is SmartPlaylistRule.Group -> {
            val childProofs = node.children.map(::proof)
            when (node.join) {
                SmartPlaylistRuleJoin.ALL -> childProofs.filterNotNull().maxOrNull()
                SmartPlaylistRuleJoin.ANY ->
                    if (childProofs.all { it != null }) childProofs.filterNotNull().minOrNull()
                    else null
            }
        }
    }
    return proof(rules)
}

/**
 * V1 and V2 use the same account-scoped read. Only a rule that guarantees
 * numeric rating evidence on all satisfying paths may break an asset tie.
 */
private suspend fun currentScopedRatedAssetHints(
    context: Context,
    source: SmartCollectionActionSource,
): Map<String, CanonicalBrowseMediaAsset> {
    val db = SmartPlaylistDefinitionDatabase.get(context)
    val minimum = when (source) {
        is SmartCollectionActionSource.Adaptive -> null
        is SmartCollectionActionSource.RecentLocalTrack -> null
        is SmartCollectionActionSource.LibraryTrack -> null
        is SmartCollectionActionSource.LibraryTracks -> null
        is SmartCollectionActionSource.SavedStaticPlaylist -> null
        is SmartCollectionActionSource.SavedStaticPlaylistFromTrack -> null
        is SmartCollectionActionSource.PreviewQuery -> source.query.minimumExplicitRating
        is SmartCollectionActionSource.PreviewV2Query ->
            source.query.guaranteedMinimumRatingForAssetHints()
        is SmartCollectionActionSource.SavedDefinition -> {
            SmartPlaylistDefinitionRepository(db.definitionDao())
                .list().firstOrNull { it.id == source.id }?.query?.minimumExplicitRating
        }
        is SmartCollectionActionSource.SavedV2Definition -> {
            SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
                .list().firstOrNull { it.id == source.id }?.query
                ?.guaranteedMinimumRatingForAssetHints()
        }
        is SmartCollectionActionSource.SavedV2PlaylistFromTrack -> {
            SmartPlaylistV2DefinitionRepository(db.v2DefinitionDao())
                .list().firstOrNull { it.id == source.id }?.query
                ?.guaranteedMinimumRatingForAssetHints()
        }
    } ?: return emptyMap()
    val account = SharedPrefsOpenSubsonicAccountPersistence(context)
    val reader = OpenSubsonicRemoteRatingReader(CanonicalCatalogDatabase.get(context)) {
        account.read()
    }
    return uniqueVerifiedRatedRemoteAssets(reader.read(), minimum)
}

/**
 * Only an explicit numerical value from a COMPLETE current-account rating
 * snapshot can establish a unique remote rendition. Multiple equally rated
 * MediaAssets, cleared/invalid/unrated evidence and account revocation cannot.
 */
internal fun uniqueVerifiedRatedRemoteAssets(
    snapshot: RemoteRatingConsumerSnapshot,
    minimum: Double,
): Map<String, CanonicalBrowseMediaAsset> {
    if (snapshot.availability != RemoteRatingAvailability.CACHED_COMPLETE) return emptyMap()
    return snapshot.items.asSequence()
        .filter {
            it.numeric.kind == RemoteNumericRatingKind.VALUE &&
                (it.numeric.value?.toDouble() ?: 0.0) >= minimum
        }
        .groupBy { it.recordingId }
        .mapNotNull { (recording, items) ->
            val uniqueAssets = items.map {
                CanonicalBrowseMediaAsset(it.mediaAssetId, it.providerKey)
            }.distinct()
            if (uniqueAssets.size == 1) recording to uniqueAssets.single() else null
        }.toMap()
}

/**
 * A rendition's stable identity is its catalog MediaAssetId and provider.
 * Cover art and timestamps can change without invalidating trusted hints.
 */
internal fun sameCanonicalAssetIdentity(
    first: CanonicalBrowseMediaAsset,
    second: CanonicalBrowseMediaAsset,
): Boolean = first.mediaAssetId == second.mediaAssetId &&
    first.providerKey == second.providerKey

/** Fail closed: never silently resolve a saved single-source playlist from another provider. */
internal fun canonicalBrowseOnlyProvider(
    library: CanonicalBrowseLibrary, providerKey: String,
): CanonicalBrowseLibrary = CanonicalBrowseLibrary(
    library.tracks.mapNotNull { track ->
        val eligible = track.availableAssets.filter { it.providerKey == providerKey }
        if (eligible.isEmpty()) null else track.copy(availableAssets = eligible)
    },
)

/** Global smart source rules must also constrain physical assets at playback. */
internal fun canonicalBrowseOnlySourceKind(
    library: CanonicalBrowseLibrary, kind: String,
): CanonicalBrowseLibrary = CanonicalBrowseLibrary(
    library.tracks.mapNotNull { track ->
        val scoped = track.availableAssets.filter { asset ->
            when (kind) {
                "local" -> asset.providerKey == RoomCanonicalQueueResolver.LOCAL_PROVIDER
                "opensubsonic" -> asset.providerKey.startsWith("opensubsonic:")
                else -> false
            }
        }
        if (scoped.isEmpty()) null else track.copy(availableAssets = scoped)
    },
)

/**
 * Catalog-only read projection. Provider configuration remains Catalog-owned,
 * using its existing local/credential-backed eligibility; no auth URLs, native
 * source IDs or network calls are passed to Playback or the UI.
 */
/**
 * Catalog must expose local assets even when no OpenSubsonic account exists.
 * Remote providers are account-scoped by Catalog; local media is not.
 */
internal fun canonicalBrowseProviderKeys(remoteProviders: Collection<String>): List<String> =
    (remoteProviders + RoomCanonicalQueueResolver.LOCAL_PROVIDER).distinct().sorted()

private suspend fun currentCanonicalBrowse(
    context: Context,
    selectedIds: List<String>,
): CanonicalBrowseLibrary {
    if (selectedIds.isEmpty()) return CanonicalBrowseLibrary.EMPTY
    require(selectedIds.size <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE)
    val dao = CanonicalCatalogDatabase.get(context).catalogDao()
    val accounts = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(context),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(context),
    )
    val providers = canonicalBrowseProviderKeys(
        OpenSubsonicLibraryProviderConfigurationReader(accounts).configuredProviderKeys(),
    )
    // Provider list is current-account scoped. No row from any other account
    // or outside the click-time RecordingId selection can enter this projection.
    val rows = selectedIds.distinct().chunked(400)
        .flatMap { dao.browseAssetsForRecordingIds(providers, it) }
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

/**
 * The collection planner intentionally runs on Dispatchers.IO. MediaController
 * methods, however, must run on the Controller's application looper. This
 * boundary switches only the synchronous command dispatch, not Room reads or
 * the asynchronous wait for Media3's result.
 */
internal suspend fun <T> onMediaControllerThread(
    dispatcher: CoroutineDispatcher,
    command: () -> T,
): T = withContext(dispatcher) { command() }

/** Actual Media3 result is awaited; command rejection is never shown as queued. */
private suspend fun submitCanonicalBatch(
    controller: MediaController?,
    plan: CanonicalCollectionIntentPlan,
): Boolean {
    val session = controller ?: run {
        SmartCollectionPlaybackTrace.stage(plan, "session_rejected", "controller_null")
        return false
    }
    val token = CanonicalBatchQueueRequests.submit(plan)
    val request = try {
        // Controller is created by the UI on the Android main looper. Access
        // from this coroutine's IO dispatcher throws Media3 wrong-thread state.
        onMediaControllerThread(Dispatchers.Main.immediate) {
            session.sendCustomCommand(
                SessionCommand(ACTION_EXECUTE_CANONICAL_BATCH, Bundle.EMPTY),
                bundleOf(EXTRA_CANONICAL_BATCH_TOKEN to token),
            )
        }
    } catch (cancelled: CancellationException) {
        CanonicalBatchQueueRequests.take(token)
        throw cancelled
    } catch (error: Exception) {
        CanonicalBatchQueueRequests.take(token)
        // Typed, privacy-safe cause: never emit exception messages or stacks.
        val reason = if (error is IllegalStateException) {
            "dispatch_illegal_state"
        } else {
            "dispatch_exception"
        }
        SmartCollectionPlaybackTrace.stage(plan, "session_rejected", reason)
        return false
    }
    SmartCollectionPlaybackTrace.stage(plan, "command_dispatched")
    return try {
        suspendCancellableCoroutine { continuation ->
            request.addListener({
                val result = runCatching {
                    request.get().resultCode == SessionResult.RESULT_SUCCESS
                }.getOrDefault(false)
                SmartCollectionPlaybackTrace.stage(
                    plan, if (result) "command_accepted" else "session_rejected",
                    if (result) "none" else "session_result_non_success",
                )
                if (continuation.isActive) continuation.resume(result)
            }, MoreExecutors.directExecutor())
        }
    } catch (cancelled: CancellationException) {
        // Once dispatched, service may already have taken the one-shot token.
        CanonicalBatchQueueRequests.take(token)
        throw cancelled
    } catch (_: Exception) {
        CanonicalBatchQueueRequests.take(token)
        SmartCollectionPlaybackTrace.stage(plan, "session_rejected", "command_result_exception")
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
            browse = { CanonicalBrowseLibrary.EMPTY },
            browseSelected = { selectedIds -> currentCanonicalBrowse(context, selectedIds) },
            sourceLimit = { source, library ->
                when (source) {
                    is SmartCollectionActionSource.RecentLocalTrack ->
                        canonicalBrowseOnlyProvider(library, RoomCanonicalQueueResolver.LOCAL_PROVIDER)
                    is SmartCollectionActionSource.LibraryTrack ->
                        if (source.sourceKey == null) library
                        else canonicalBrowseOnlyProvider(library, source.sourceKey)
                    is SmartCollectionActionSource.LibraryTracks ->
                        if (source.sourceKey == null) library
                        else canonicalBrowseOnlyProvider(library, source.sourceKey)
                    is SmartCollectionActionSource.SavedStaticPlaylist,
                    is SmartCollectionActionSource.SavedStaticPlaylistFromTrack -> {
                        val id = when (source) {
                            is SmartCollectionActionSource.SavedStaticPlaylist -> source.id
                            is SmartCollectionActionSource.SavedStaticPlaylistFromTrack -> source.id
                            else -> error("Not a static playlist source")
                        }
                        val chosen = CanonicalStaticPlaylistRepository(
                            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
                        ).list().firstOrNull { it.id == id }?.sourceProviderKey
                        if (chosen == null) library
                            else canonicalBrowseOnlyProvider(library, chosen)
                    }
                    is SmartCollectionActionSource.SavedV2Definition,
                    is SmartCollectionActionSource.SavedV2PlaylistFromTrack -> {
                        val id = when (source) {
                            is SmartCollectionActionSource.SavedV2Definition -> source.id
                            is SmartCollectionActionSource.SavedV2PlaylistFromTrack -> source.id
                            else -> error("Not a smart playlist source")
                        }
                        val query = SmartPlaylistV2DefinitionRepository(
                            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
                        ).list().firstOrNull { it.id == id }?.query
                        val kind = query?.let { smartRuleGlobalSource(it.rules) }
                        if (kind == null) library
                            else canonicalBrowseOnlySourceKind(library, kind)
                    }
                    is SmartCollectionActionSource.PreviewV2Query -> {
                        val kind = smartRuleGlobalSource(source.query.rules)
                        if (kind == null) library
                            else canonicalBrowseOnlySourceKind(library, kind)
                    }
                    else -> library
                }
            },
            planner = CanonicalCollectionIntentPlanner(androidCanonicalQueueResolver(context)),
            submit = { plan -> submitCanonicalBatch(controller, plan) },
            verifiedRemoteAssets = { source -> currentScopedRatedAssetHints(context, source) },
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
                    // Command acceptance is not audible evidence. The sink must
                    // confirm audio samples before playback can be called successful.
                    SmartCollectionBatchResult.Submitted ->
                        if (intent == CanonicalCollectionIntent.PLAY_NOW) null else added
                    SmartCollectionBatchResult.Empty -> empty
                    else -> failed
                }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}


/**
 * Search discovery and Playback are deliberately separate: an MBID may exist
 * without an audio asset. A tap enters the existing B9/B10 canonical planner,
 * which filters configured providers and revalidates at Media3 execution.
 * No YouTube video IDs, fallback streams or native account URLs are created.
 */
@Composable
internal fun rememberMusicBrainzCatalogAction(
    controller: MediaController?,
): (String, CanonicalCollectionIntent) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val working = remember { mutableStateOf(false) }
    val gate = remember(context) {
        MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { mbid ->
                CanonicalCatalogDatabase.get(context).catalogDao()
                    .entityIdForExternalIdentity("musicbrainz", "recording", mbid)
            },
            // Never materialize an unrestricted catalog for one MBID tap.
            browse = { CanonicalBrowseLibrary.EMPTY },
            browseSelected = { ids -> currentCanonicalBrowse(context, ids) },
            planner = CanonicalCollectionIntentPlanner(androidCanonicalQueueResolver(context)),
        )
    }
    val added = stringResource(Res.string.smart_collection_batch_added)
    val failed = stringResource(Res.string.smart_collection_batch_failed)
    return { mbid, intent ->
        if (!working.value) {
            working.value = true
            scope.launch {
                val submitted = try {
                    withContext(Dispatchers.IO) {
                        val plan = gate.plan(mbid, intent) ?: return@withContext false
                        submitCanonicalBatch(controller, plan)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                } finally {
                    working.value = false
                }
                if (!submitted || intent != CanonicalCollectionIntent.PLAY_NOW) {
                    Toast.makeText(context, if (submitted) added else failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
