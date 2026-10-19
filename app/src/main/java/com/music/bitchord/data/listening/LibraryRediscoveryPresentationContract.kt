package com.music.bitchord.data.listening

/**
 * Availability was checked by the existing Library candidate source, not by this adapter.
 * L2 must revalidate the selected Recording on navigation because snapshots can become stale.
 */
internal enum class RediscoveryPresentationAvailability { AVAILABLE_AT_SNAPSHOT }

/** UI-neutral, first-party-only projection. No mediaAsset/provider/URI or mutable ranker models. */
internal data class RediscoveryPresentationItem(
    val recordingId: String,
    val shelfPosition: Int,
    val rankPosition: Int,
    val lastAudibleListenAtEpochMs: Long,
    val lastInteractionAtEpochMs: Long?,
    val timeSinceLastAudibleMs: Long,
    val daysSinceLastAudible: Long,
    val reasonCodes: List<RediscoveryReason>,
    val availability: RediscoveryPresentationAvailability,
) {
    val canNavigateToCanonicalRecording: Boolean
        get() = availability == RediscoveryPresentationAvailability.AVAILABLE_AT_SNAPSHOT
}

/**
 * Maps only #43's already-selected preview; never ranks, rechecks preference, reads or writes data.
 * Source order remains the only order. A UI can render labels from reasonCodes without new
 * eligibility logic. Empty output renders no shelf. The strict shelf ceiling is five.
 */
internal class LibraryRediscoveryPresentationContract {
    fun present(shortlist: List<RediscoveryPreviewCandidate>): List<RediscoveryPresentationItem> {
        val selected = shortlist.take(MAX_SHELF_ITEMS)
        require(selected.map { it.recordingId }.distinct().size == selected.size) {
            "Presentation requires unique canonical RecordingIds"
        }
        return selected.mapIndexed { index, candidate ->
            require(candidate.recordingId.isNotBlank() && candidate.rankPosition > 0 &&
                candidate.lastAudibleListenAtEpochMs >= 0L && candidate.timeSinceLastAudibleMs >= 0L
            ) { "Invalid rediscovery preview provenance" }
            RediscoveryPresentationItem(
                recordingId = candidate.recordingId,
                shelfPosition = index + 1,
                rankPosition = candidate.rankPosition,
                lastAudibleListenAtEpochMs = candidate.lastAudibleListenAtEpochMs,
                lastInteractionAtEpochMs = candidate.lastInteractionAtEpochMs,
                timeSinceLastAudibleMs = candidate.timeSinceLastAudibleMs,
                daysSinceLastAudible = candidate.timeSinceLastAudibleMs / MILLIS_PER_DAY,
                reasonCodes = RediscoveryReason.values().filter { it in candidate.reasons },
                availability = RediscoveryPresentationAvailability.AVAILABLE_AT_SNAPSHOT,
            )
        }
    }

    private companion object {
        const val MAX_SHELF_ITEMS = 5
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
