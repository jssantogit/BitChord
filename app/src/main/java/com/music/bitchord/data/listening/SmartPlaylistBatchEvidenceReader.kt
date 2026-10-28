package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalCatalogDao

/**
 * Read-only evidence from existing canonical/Listening Room tables.
 * Each invocation is bounded below SQLite's bind-parameter limit.
 * Availability and account scoping are decided before this reader is called.
 */
internal fun interface SmartPlaylistBatchEvidenceReader {
    suspend fun read(recordingIds: List<String>): List<RuleBasedRecordingCandidate>
}

internal class RoomSmartPlaylistBatchEvidenceReader(
    private val catalog: CanonicalCatalogDao,
    private val listening: ListeningEventDao,
    private val ratings: RecordingRatingDao,
) : SmartPlaylistBatchEvidenceReader {
    override suspend fun read(recordingIds: List<String>): List<RuleBasedRecordingCandidate> {
        require(recordingIds.size <= 400) { "Smart playlist evidence batch exceeds SQLite-safe bound" }
        if (recordingIds.isEmpty()) return emptyList()
        require(recordingIds.all(String::isNotBlank))
        val recordings = catalog.recordingsByIds(recordingIds).associateBy { it.id }
        val listeningById = listening.smartPlaylistBehaviorForIds(recordingIds)
            .associateBy { it.recordingId }
        val ratingsById = ratings.currentRatingsForIds(recordingIds)
            .associateBy { it.recordingId }
        val releaseTitlesById = catalog.recordingReleaseTitlesByIds(recordingIds)
            .groupBy { it.recordingId }
            .mapValues { (_, memberships) ->
                memberships.map { it.releaseTitle }.distinct().sorted()
            }
        // Missing canonical metadata means the Recording cannot be promoted
        // from a provider/native id; no fake row is synthesized.
        return recordingIds.distinct().mapNotNull { id ->
            val recording = recordings[id] ?: return@mapNotNull null
            val events = listeningById[id]
            RuleBasedRecordingCandidate(
                recordingId = recording.id,
                explicitRating = ratingsById[id]?.ratingValue,
                audibleCompletions = events?.completions ?: 0L,
                lastAudibleAtEpochMs = events?.lastAudibleAtEpochMs,
                durationMs = recording.durationMs,
                available = true,
                title = recording.title,
                artistDisplay = recording.artistDisplay,
                earlySkips = events?.earlySkips ?: 0L,
                lateSkips = events?.lateSkips ?: 0L,
                releaseTitles = releaseTitlesById[id].orEmpty(),
            )
        }
    }
}
