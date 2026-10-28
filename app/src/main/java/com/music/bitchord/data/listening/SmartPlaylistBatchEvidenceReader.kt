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

/**
 * MusicBrainz release dates can be YYYY, YYYY-MM or YYYY-MM-DD. Only a
 * syntactically and calendrically valid official date yields an integer year.
 * File-added/modified timestamps are not release years.
 */
internal fun parseTrustedMusicBrainzReleaseYear(value: String?): Int? {
    val date = value ?: return null
    if (!Regex("""\d{4}(-\d{2}(-\d{2})?)?""").matches(date)) return null
    val parts = date.split('-')
    val year = parts[0].toIntOrNull()?.takeIf { it in 1000..9999 } ?: return null
    if (parts.size >= 2) {
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        if (parts.size == 3) {
            val day = parts[2].toIntOrNull() ?: return null
            val leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0))
            val limit = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            if (day !in 1..limit) return null
        }
    }
    return year
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
        val dateRowsById = catalog.recordingReleaseDatesByIds(recordingIds)
            .groupBy { it.recordingId }
        val albumArtistRowsById = catalog.recordingAlbumArtistCreditsByIds(recordingIds)
            .groupBy { it.recordingId }
        val trackPositionsById = catalog.recordingTrackPositionsByIds(recordingIds)
            .groupBy { it.recordingId }
        // Missing canonical metadata means the Recording cannot be promoted
        // from a provider/native id; no fake row is synthesized.
        return recordingIds.distinct().mapNotNull { id ->
            val recording = recordings[id] ?: return@mapNotNull null
            val events = listeningById[id]
            val datedReleases = dateRowsById[id].orEmpty()
            val parsedYears = datedReleases.map { parseTrustedMusicBrainzReleaseYear(it.releaseDate) }
            val albumArtistRows = albumArtistRowsById[id].orEmpty()
            val trackPositionRows = trackPositionsById[id].orEmpty()
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
                releaseYears = parsedYears.filterNotNull().distinct().sorted(),
                releaseYearEvidenceUnknown = datedReleases.isEmpty() ||
                    parsedYears.any { it == null },
                albumArtistNames = albumArtistRows.mapNotNull {
                    it.artistName?.trim()?.takeIf(String::isNotEmpty)
                }.distinct().sorted(),
                albumArtistEvidenceUnknown = albumArtistRows.isEmpty() ||
                    albumArtistRows.any { it.artistName.isNullOrBlank() },
                trackNumbers = trackPositionRows.mapNotNull {
                    it.trackNumber?.takeIf { number -> number > 0 }
                }.distinct().sorted(),
                trackNumberEvidenceUnknown = trackPositionRows.isEmpty() ||
                    trackPositionRows.any { (it.trackNumber ?: 0) <= 0 },
            )
        }
    }
}
