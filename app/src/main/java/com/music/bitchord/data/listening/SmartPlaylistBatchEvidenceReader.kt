package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalLocalLibrary

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
    // Existing direct readers and tests keep complete evidence semantics.
    override suspend fun read(recordingIds: List<String>): List<RuleBasedRecordingCandidate> =
        read(recordingIds, SmartPlaylistRuleField.entries.toSet())

    /** The production preview requests only the relationships used by the AST. */
    internal suspend fun read(
        recordingIds: List<String>,
        requiredFields: Set<SmartPlaylistRuleField>,
    ): List<RuleBasedRecordingCandidate> {
        require(recordingIds.size <= 400) { "Smart playlist evidence batch exceeds SQLite-safe bound" }
        if (recordingIds.isEmpty()) return emptyList()
        require(recordingIds.all(String::isNotBlank))
        val recordings = catalog.recordingsByIds(recordingIds).associateBy { it.id }
        val needsListening = requiredFields.any {
            it == SmartPlaylistRuleField.AUDIBLE_COMPLETIONS ||
                it == SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ||
                it == SmartPlaylistRuleField.EARLY_SKIPS ||
                it == SmartPlaylistRuleField.LATE_SKIPS ||
                it == SmartPlaylistRuleField.TOTAL_SKIPS ||
                it == SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP
        }
        val needsRatings = SmartPlaylistRuleField.EXPLICIT_RATING in requiredFields ||
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING in requiredFields
        val listeningById = if (needsListening) {
            listening.smartPlaylistBehaviorForIds(recordingIds).associateBy { it.recordingId }
        } else emptyMap()
        val ratingsById = if (needsRatings) {
            ratings.currentRatingsForIds(recordingIds).associateBy { it.recordingId }
        } else emptyMap()
        val releaseMembershipsById = if (SmartPlaylistRuleField.RELEASE_TITLE in requiredFields) {
            catalog.recordingReleaseTitlesByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val dateRowsById = if (SmartPlaylistRuleField.RELEASE_YEAR in requiredFields) {
            catalog.recordingReleaseDatesByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val albumArtistRowsById = if (SmartPlaylistRuleField.ALBUM_ARTIST in requiredFields) {
            catalog.recordingAlbumArtistCreditsByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val trackPositionsById = if (SmartPlaylistRuleField.TRACK_NUMBER in requiredFields) {
            catalog.recordingTrackPositionsByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val discNumbersById = if (SmartPlaylistRuleField.DISC_NUMBER in requiredFields) {
            catalog.recordingDiscNumbersByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val groupTypesById = if (SmartPlaylistRuleField.RELEASE_GROUP_TYPE in requiredFields) {
            catalog.recordingReleaseGroupTypesByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val trackArtistsById = if (SmartPlaylistRuleField.TRACK_ARTIST in requiredFields) {
            catalog.recordingTrackArtistCreditsByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val artworkById = if (SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE in requiredFields) {
            catalog.recordingReleaseArtworkByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val countriesById = if (SmartPlaylistRuleField.ARTIST_COUNTRY in requiredFields) {
            catalog.recordingArtistCountriesByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val originalReleaseDatesById = if (
            SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR in requiredFields
        ) {
            catalog.recordingOriginalReleaseDatesByIds(recordingIds).groupBy { it.recordingId }
        } else emptyMap()
        val localAddedById = if (
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED in requiredFields
        ) {
            catalog.localAddedDatesByRecordingIds(
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, recordingIds,
            ).groupBy { it.recordingId }
        } else emptyMap()
        val localPathsById = if (
            SmartPlaylistRuleField.LOCAL_FOLDER in requiredFields ||
            SmartPlaylistRuleField.LOCAL_FILE_PATH in requiredFields ||
            SmartPlaylistRuleField.LOCAL_FILE_EXTENSION in requiredFields ||
            SmartPlaylistRuleField.FILE_PATH in requiredFields ||
            SmartPlaylistRuleField.FOLDER_PATH in requiredFields
        ) {
            catalog.localPathsByRecordingIds(
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, recordingIds,
            ).groupBy { it.recordingId }
        } else emptyMap()
        val localAudioTagsById = if (
            SmartPlaylistRuleField.LOCAL_GENRE in requiredFields ||
            SmartPlaylistRuleField.GENRE in requiredFields ||
            SmartPlaylistRuleField.LOCAL_BITRATE_KBPS in requiredFields
        ) {
            catalog.localAudioTagsByRecordingIds(
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, recordingIds,
            ).groupBy { it.recordingId }
        } else emptyMap()
        // Missing canonical metadata means the Recording cannot be promoted
        // from a provider/native id; no fake row is synthesized.
        return recordingIds.distinct().mapNotNull { id ->
            val recording = recordings[id] ?: return@mapNotNull null
            val events = listeningById[id]
            val datedReleases = dateRowsById[id].orEmpty()
            val parsedYears = datedReleases.map { parseTrustedMusicBrainzReleaseYear(it.releaseDate) }
            val albumArtistRows = albumArtistRowsById[id].orEmpty()
            val trackPositionRows = trackPositionsById[id].orEmpty()
            val discRows = discNumbersById[id].orEmpty()
            val groupTypeRows = groupTypesById[id].orEmpty()
            val trackArtistRows = trackArtistsById[id].orEmpty()
            val artistCountryRows = countriesById[id].orEmpty()
            val originalDateRows = originalReleaseDatesById[id].orEmpty()
            val parsedOriginalYears = originalDateRows.map {
                parseTrustedMusicBrainzReleaseYear(it.firstReleaseDate)
            }
            val addedRows = localAddedById[id].orEmpty()
            val localPathRows = localPathsById[id].orEmpty()
            val audioTagRows = localAudioTagsById[id].orEmpty()
            val trustedGenres = audioTagRows.mapNotNull {
                it.localGenre?.trim()?.takeIf(String::isNotBlank)
            }.distinct().sorted()
            val trustedBitrates = audioTagRows.mapNotNull {
                it.localBitrateBps?.takeIf { bitrate -> bitrate > 0L }
                    ?.toDouble()?.div(1000.0)
            }.distinct().sorted()
            val usablePaths = localPathRows.mapNotNull {
                normalizeLocalSmartFilterPath(it.path)
            }.distinct().sorted()
            val addedValues = addedRows.map { row ->
                row.dateAddedSeconds?.takeIf { second ->
                    second > 0L && second <= Long.MAX_VALUE / 1000L
                }?.times(1000L)
            }
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
                lastSkippedAtEpochMs = events?.lastSkippedAtEpochMs,
                releaseTitles = releaseMembershipsById[id].orEmpty()
                    .map { it.releaseTitle }.distinct().sorted(),
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
                trackArtistNames = trackArtistRows.mapNotNull {
                    it.artistName?.trim()?.takeIf(String::isNotEmpty)
                }.distinct().sorted(),
                trackArtistEvidenceUnknown = trackArtistRows.isEmpty() ||
                    trackArtistRows.any { it.artistName.isNullOrBlank() },
                localRatingUpdatedAtEpochMs = if (
                    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING in requiredFields
                ) ratingsById[id]?.ratedAtEpochMs else null,
                localRatingDateEvidenceKnown =
                    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING in requiredFields,
                hasReleaseArtworkReference = artworkById[id].orEmpty()
                    .any { !it.artworkUri.isNullOrBlank() },
                localAddedEpochMs = addedValues.filterNotNull().distinct().sorted(),
                localAddedDateEvidenceUnknown = addedRows.isEmpty() ||
                    addedValues.any { it == null },
                localFilePaths = usablePaths,
                localFilePathsComplete = localPathRows.isNotEmpty() &&
                    localPathRows.all { normalizeLocalSmartFilterPath(it.path) != null },
                localGenres = trustedGenres,
                localGenresComplete = audioTagRows.isNotEmpty() &&
                    audioTagRows.all { !it.localGenre.isNullOrBlank() },
                localBitratesKbps = trustedBitrates,
                localBitrateEvidenceComplete = audioTagRows.isNotEmpty() &&
                    audioTagRows.all { (it.localBitrateBps ?: 0L) > 0L },
                canonicalReleaseIds = releaseMembershipsById[id].orEmpty()
                    .map { it.releaseId }.distinct().sorted(),
                artistCountryCodes = artistCountryRows.mapNotNull {
                    it.country?.trim()?.uppercase(java.util.Locale.ROOT)
                        ?.takeIf { code -> code.length == 2 && code.all(Char::isLetter) }
                }.distinct().sorted(),
                artistCountryEvidenceUnknown = artistCountryRows.isEmpty() ||
                    artistCountryRows.any { row ->
                        row.country?.trim()?.let { code ->
                            code.length != 2 || !code.all(Char::isLetter)
                        } != false
                    },
                originalReleaseYears = parsedOriginalYears.filterNotNull().distinct().sorted(),
                originalReleaseYearEvidenceUnknown = originalDateRows.isEmpty() ||
                    parsedOriginalYears.any { it == null },
                discNumbers = discRows.mapNotNull { it.discNumber?.takeIf { n -> n > 0 } }
                    .distinct().sorted(),
                discNumberEvidenceUnknown = discRows.isEmpty() ||
                    discRows.any { (it.discNumber ?: 0) <= 0 },
                releaseGroupTypes = groupTypeRows.mapNotNull {
                    it.primaryType?.trim()?.takeIf(String::isNotBlank)
                }.distinct().sorted(),
                releaseGroupTypeEvidenceUnknown = groupTypeRows.isEmpty() ||
                    groupTypeRows.any { it.primaryType.isNullOrBlank() },
            )
        }
    }
}
