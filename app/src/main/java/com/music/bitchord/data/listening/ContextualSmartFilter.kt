package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalScopedTagRow
import kotlinx.serialization.json.Json
import com.music.bitchord.data.catalog.CanonicalLocalLibrary

/**
 * First contextual (SONGS) filter surface for a provider-scoped canonical browse.
 * A full smart playlist preview is intentionally NOT used: it has a different
 * global-account availability scope, a saved query limit, and more evidence.
 *
 * Only persisted canonical title/artist/duration and the proven provider category
 * are complete for this projection. An unfamiliar provider remains UNKNOWN.
 * Unknown ratings, last plays, credits or static playlist references must never
 * become IS_MISSING or pass NOT. A later scoped evidence adapter can expand this
 * whitelist without changing the saved filter schema or recording identity.
 */
/** Only these two proven canonical provider categories have Source Kind semantics. */
internal fun verifiedContextualSourceKind(providerKey: String?): SmartPlaylistSourceKind? =
    when {
        providerKey == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER ->
            SmartPlaylistSourceKind.LOCAL
        providerKey?.startsWith("opensubsonic:") == true &&
            providerKey.substringAfter(':').isNotBlank() ->
            SmartPlaylistSourceKind.OPENSUBSONIC
        else -> null
    }

internal fun SavedSmartPlaylistV2Filter.isSupportedForCanonicalSongBrowse(
    providerKey: String? = null,
): Boolean {
    fun supported(rule: SmartPlaylistRule): Boolean = when (rule) {
        is SmartPlaylistRule.Comparison ->
            rule.field == SmartPlaylistRuleField.TITLE ||
                rule.field == SmartPlaylistRuleField.ARTIST_DISPLAY ||
                rule.field == SmartPlaylistRuleField.DURATION_MS ||
                rule.field == SmartPlaylistRuleField.SOURCE_KIND ||
                (rule.field in setOf(SmartPlaylistRuleField.GENRE, SmartPlaylistRuleField.MOOD,
                    SmartPlaylistRuleField.BPM, SmartPlaylistRuleField.STYLE,
                    SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleField.FILE_PATH,
                    SmartPlaylistRuleField.FOLDER_PATH) &&
                    verifiedContextualSourceKind(providerKey) != null) ||
                (rule.field == SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED &&
                    providerKey == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
        is SmartPlaylistRule.Group -> rule.children.isNotEmpty() &&
            rule.children.all(::supported)
        is SmartPlaylistRule.Not -> supported(rule.child)
        is SmartPlaylistRule.Reference -> false
    }
    return supported(rules)
}

/**
 * Pure, ID-preserving filter for a *pre-scoped* list of canonical recordings.
 *
 * Filtering runs in bounded 400-row blocks because a stored filter has no
 * implicit 100/10,000-song cap. The result retains the provider browse order,
 * canonical Recordings and playback assets from its input unchanged.
 */
/** Invalid JSON, an absent optional field, or a malformed value remains UNKNOWN. */
private fun parseScopedBpmValues(raw: String?): List<Double>? = raw?.let {
    runCatching { Json.decodeFromString<List<Double>>(it) }.getOrNull()?.takeIf { xs ->
        xs.all { x -> x.isFinite() && x > 0.0 && x <= 999.0 }
    }?.distinct()
}

private fun parseScopedAssetTags(json: String?): List<String>? =
    json?.let { serialized ->
        runCatching { Json.decodeFromString<List<String>>(serialized) }
            .getOrNull()?.map(String::trim)
            ?.takeIf { items -> items.all(String::isNotBlank) }
            ?.distinct()
    }

internal fun filterCanonicalBrowseSongs(
    tracks: List<CanonicalBrowseTrack>,
    filter: SavedSmartPlaylistV2Filter,
    providerKey: String,
    localAddedDatesByRecording: Map<String, List<Long?>>? = null,
    scopedTagRowsByRecording: Map<String, List<CanonicalScopedTagRow>>? = null,
): List<CanonicalBrowseTrack> {
    require(providerKey.isNotBlank()) { "A configured provider identity is required" }
    require(filter.isSupportedForCanonicalSongBrowse(providerKey)) {
        "This saved filter needs evidence unavailable in canonical Songs browse"
    }
    require(tracks.all { track ->
        track.recordingId.isNotBlank() && track.availableAssets.isNotEmpty() &&
            track.availableAssets.all { it.providerKey == providerKey }
    }) {
        "A recording from another provider/account cannot enter the filtered browse"
    }
    require(tracks.distinctBy { it.recordingId }.size == tracks.size) {
        "Canonical browse must have one row per RecordingId"
    }
    val needsLocalAdded = SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED in
        filter.rules.referencedFields()
    if (needsLocalAdded) {
        require(localAddedDatesByRecording != null) {
            "Local-file-added date requires persisted MediaStore evidence"
        }
    }
    val needsTags = filter.rules.referencedFields().any {
        it == SmartPlaylistRuleField.GENRE || it == SmartPlaylistRuleField.MOOD ||
            it == SmartPlaylistRuleField.BPM || it == SmartPlaylistRuleField.STYLE ||
            it == SmartPlaylistRuleField.COMPOSER ||
            it == SmartPlaylistRuleField.FILE_PATH || it == SmartPlaylistRuleField.FOLDER_PATH
    }
    if (needsTags) require(scopedTagRowsByRecording != null) {
        "Provider tag rules require a current scoped Room snapshot"
    }
    val nowMs = System.currentTimeMillis()
    val sourceKind = verifiedContextualSourceKind(providerKey)
    val query = SmartPlaylistV2Query(
        rules = filter.rules,
        sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        limit = 400,
    )
    val engine = SmartPlaylistV2RuleEngine()
    return tracks.chunked(400).flatMap { chunk ->
        val candidates = chunk.map { song ->
            val timestamps = localAddedDatesByRecording?.get(song.recordingId)
            val validTimestamps = timestamps.orEmpty().mapNotNull { seconds ->
                seconds?.takeIf { it in 1L..(Long.MAX_VALUE / 1_000L) }
                    ?.times(1_000L)
            }
            val timestampsUnknown = timestamps == null ||
                timestamps.size != validTimestamps.size ||
                validTimestamps.any { it > nowMs }
            val tagRows = scopedTagRowsByRecording?.get(song.recordingId).orEmpty()
            val visibleAssetIds = song.availableAssets.map { it.mediaAssetId }.toSet()
            val validRows = tagRows.filter { it.mediaAssetId in visibleAssetIds }
            val completeRows = tagRows.size == visibleAssetIds.size &&
                validRows.size == visibleAssetIds.size &&
                validRows.map { it.mediaAssetId }.toSet() == visibleAssetIds
            val genres = validRows.map { parseScopedAssetTags(it.genresJson) }
            val moods = validRows.map { parseScopedAssetTags(it.moodsJson) }
            val bpms = validRows.map { parseScopedBpmValues(it.bpmsJson) }
            val styles = validRows.map { parseScopedAssetTags(it.stylesJson) }
            val composers = validRows.map { parseScopedAssetTags(it.composersJson) }
            val paths = validRows.map { canonicalAssetFilePath(it.assetPath) }
            val folders = validRows.map { canonicalAssetFolderPath(it.assetPath) }
            RuleBasedRecordingCandidate(
                recordingId = song.recordingId,
                explicitRating = null,
                audibleCompletions = 0,
                lastAudibleAtEpochMs = null,
                durationMs = song.durationMs,
                available = true,
                title = song.title,
                artistDisplay = song.artistDisplay,
                sourceKinds = sourceKind?.let(::setOf),
                genres = genres.filterNotNull().flatten().distinct(),
                genreEvidenceUnknown = !completeRows || genres.any { it == null },
                moods = moods.filterNotNull().flatten().distinct(),
                moodEvidenceUnknown = !completeRows || moods.any { it == null },
                bpms = bpms.filterNotNull().flatten().distinct(),
                bpmEvidenceUnknown = !completeRows || bpms.any { it == null },
                styles = styles.filterNotNull().flatten().distinct(),
                styleEvidenceUnknown = !completeRows || styles.any { it == null },
                composers = composers.filterNotNull().flatten().distinct(),
                composerEvidenceUnknown = !completeRows || composers.any { it == null },
                filePaths = paths.filterNotNull().distinct(),
                filePathEvidenceUnknown = !completeRows || paths.any { it == null },
                folderPaths = folders.filterNotNull().distinct(),
                folderPathEvidenceUnknown = !completeRows || folders.any { it == null },
                localAddedEpochMs = validTimestamps,
                localAddedDateEvidenceUnknown = timestampsUnknown,
            )
        }
        val matches = engine.preview(candidates, query).mapTo(hashSetOf()) { it.recordingId }
        chunk.filter { it.recordingId in matches }
    }
}
