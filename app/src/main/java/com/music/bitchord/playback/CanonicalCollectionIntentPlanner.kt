package com.music.bitchord.playback

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import kotlinx.coroutines.CancellationException

/**
 * Explicit user intent only. Preview never edits the queue, creates playback
 * history, starts a stream or infers an intent from a Library impression.
 */
internal enum class CanonicalCollectionIntent { PLAY_NOW, ADD_NEXT, ADD_TO_QUEUE }

internal enum class CollectionIntentRejection {
    INVALID_RECORDING, RECORDING_UNAVAILABLE, AMBIGUOUS_RECORDING,
    NO_ASSET, AMBIGUOUS_ASSET, INVALID_ASSET, DUPLICATE,
    LIMIT_EXCEEDED, RESOLUTION_MISMATCH,
}

/** No raw URLs, provider-native IDs or credentials are included in rejection data. */
internal data class CanonicalCollectionRejected(
    val inputIndex: Int,
    val reason: CollectionIntentRejection,
)

/** A unique, existing catalog selection and its safe display metadata. */
internal data class CanonicalCollectionSelection(
    val identity: CanonicalQueueIdentity,
    val title: String,
    val artistDisplay: String,
) {
    val recordingId: String get() = identity.recordingId
}

/**
 * Read-only snapshot. B10 MUST revalidate against a fresh Catalog projection,
 * session generation and player timeline before committing a single edit.
 */
internal data class CanonicalCollectionIntentPlan(
    val intent: CanonicalCollectionIntent,
    val selections: List<CanonicalCollectionSelection>,
    val rejected: List<CanonicalCollectionRejected>,
)

/**
 * Playback-owned deterministic adapter for Listening L13.
 *
 * The caller supplies an existing provider-filtered CanonicalBrowseLibrary
 * snapshot from Catalog. Only a uniquely proven rendition is eligible; the
 * caller must never substitute videoId, title, sourceKey or native stream URL.
 * Asset availability can change after preview, so a plan is not executable
 * authorization until B10 revalidates it.
 */
internal class CanonicalCollectionIntentPlanner(
    private val resolver: CanonicalQueueResolver,
) {
    companion object {
        const val MAX_INPUT_SIZE = 100
        private const val MAX_ID_CHARS = 256
    }

    suspend fun preview(
        recordingIds: List<String>,
        intent: CanonicalCollectionIntent,
        library: CanonicalBrowseLibrary,
        deduplicateByRecording: Boolean = true,
        preferSingleLocalCopy: Boolean = false,
    ): CanonicalCollectionIntentPlan {
        val rejected = mutableListOf<CanonicalCollectionRejected>()
        val rowsById = library.tracks.groupBy(CanonicalBrowseTrack::recordingId)
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<Pair<Int, CanonicalCollectionSelection>>()

        recordingIds.forEachIndexed { index, recordingId ->
            fun reject(reason: CollectionIntentRejection) {
                rejected += CanonicalCollectionRejected(index, reason)
            }
            if (index >= MAX_INPUT_SIZE) {
                reject(CollectionIntentRejection.LIMIT_EXCEEDED)
                return@forEachIndexed
            }
            if (!safePart(recordingId)) {
                reject(CollectionIntentRejection.INVALID_RECORDING)
                return@forEachIndexed
            }
            if (deduplicateByRecording && !seen.add(recordingId)) {
                reject(CollectionIntentRejection.DUPLICATE)
                return@forEachIndexed
            }
            val matches = rowsById[recordingId].orEmpty()
            if (matches.isEmpty()) {
                reject(CollectionIntentRejection.RECORDING_UNAVAILABLE)
                return@forEachIndexed
            }
            if (matches.size != 1) {
                reject(CollectionIntentRejection.AMBIGUOUS_RECORDING)
                return@forEachIndexed
            }
            val row = matches.single()
            val presentAssets = row.availableAssets.distinctBy { it.mediaAssetId to it.providerKey }
            // Explicit smart-collection policy, never a fallback to a random rendition:
            // one local copy outranks remote duplicates only when the local asset is
            // unique and still resolves. Two locals or remote-only ambiguity fail closed.
            val local = presentAssets.filter {
                it.providerKey == RoomCanonicalQueueResolver.LOCAL_PROVIDER
            }
            val assets = if (preferSingleLocalCopy && local.size == 1) local else presentAssets
            if (assets.isEmpty()) {
                reject(CollectionIntentRejection.NO_ASSET)
                return@forEachIndexed
            }
            if (assets.size != 1) {
                reject(CollectionIntentRejection.AMBIGUOUS_ASSET)
                return@forEachIndexed
            }
            val asset = assets.single()
            if (!safePart(asset.mediaAssetId) || !safePart(asset.providerKey)) {
                reject(CollectionIntentRejection.INVALID_ASSET)
                return@forEachIndexed
            }
            candidates += index to CanonicalCollectionSelection(
                identity = CanonicalQueueIdentity(recordingId, asset.mediaAssetId, asset.providerKey),
                title = row.title,
                artistDisplay = row.artistDisplay,
            )
        }

        if (candidates.isEmpty()) return CanonicalCollectionIntentPlan(intent, emptyList(), rejected)

        // Resolve in one bounded batch. Never catch cancellation as ordinary
        // unavailability or return half a plan from a cancelled user action.
        val identities = candidates.map { it.second.identity }
        val resolved = try {
            resolver.resolveAll(identities)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

        val accepted = mutableListOf<CanonicalCollectionSelection>()
        candidates.forEachIndexed { ordinal, (index, selection) ->
            val result = resolved.getOrNull(ordinal)
            val expected = selection.identity
            val safe = result != null && result.identity == expected && matchesSelectedUri(result, expected)
            if (resolved.size == candidates.size && safe) {
                accepted += selection
            } else {
                rejected += CanonicalCollectionRejected(index, CollectionIntentRejection.RESOLUTION_MISMATCH)
            }
        }
        return CanonicalCollectionIntentPlan(intent, accepted.toList(), rejected.sortedBy { it.inputIndex })
    }

    private fun safePart(value: String): Boolean =
        value.isNotBlank() && value == value.trim() && value.length <= MAX_ID_CHARS &&
            value.none { it.isISOControl() }

    private fun matchesSelectedUri(
        resolved: CanonicalPlayableAsset,
        expected: CanonicalQueueIdentity,
    ): Boolean {
        if (resolved.uri.isBlank()) return false
        if (expected.providerKey == RoomCanonicalQueueResolver.LOCAL_PROVIDER) {
            // A remote logical URI cannot impersonate a local asset.
            return !CanonicalRemotePlaybackUri.isCanonical(resolved.uri)
        }
        val asset = expected.mediaAssetId ?: return false
        val provider = expected.providerKey ?: return false
        return resolved.uri == CanonicalRemotePlaybackUri.encode(
            CanonicalRemotePlaybackRequest(expected.recordingId, asset, provider),
        )
    }
}
