package com.music.bitchord.ui

import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.ui.screens.CanonicalCatalogDetailPage
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import kotlinx.coroutines.CancellationException

/**
 * Publish only the release tracklist that is actually in the persisted cache.
 * If reconciliation refuses new data, the prior coherent edition remains the
 * display source. No audio asset is synthesized by this metadata page.
 */
internal suspend fun canonicalPersistedReleaseDetailPage(
    database: CanonicalCatalogDatabase,
    index: MusicBrainzCatalogIndex,
    incoming: MusicBrainzCatalogReleaseDetail,
    title: String,
    summary: String?,
    artworkUri: String?,
    includeTrackPosition: Boolean = false,
    inheritedFromGroup: Boolean = false,
    expectedGroupMbid: String? = null,
): CanonicalCatalogDetailPage? {
    val persisted = try {
        index.persistReleaseDetail(incoming) != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    val selected = index.cachedRelease(incoming.musicBrainzId) ?: return null
    if (expectedGroupMbid != null &&
        !selected.groupMbid.equals(expectedGroupMbid, ignoreCase = true)
    ) return null
    if (selected.tracks.isEmpty()) return null
    val available = database.catalogDao().musicBrainzRecordingMbidsWithAssets(
        selected.tracks.map { it.recordingMbid },
    ).toSet()
    val rows = selected.tracks.map { track ->
        CanonicalCatalogSearchRow(
            musicBrainzId = track.recordingMbid,
            kind = CanonicalSearchKind.RECORDING,
            title = if (includeTrackPosition) "${track.mediumNumber}.${track.trackNumber}  ${track.title}"
                else track.title,
            subtitle = track.artistDisplay ?: selected.artistDisplay,
            hasCatalogedAsset = track.recordingMbid in available,
            fromOfflineCache = !persisted,
            trackMbid = track.trackMbid,
            durationMs = track.durationMs,
        )
    }
    return CanonicalCatalogDetailPage(
        title = title,
        summary = if (persisted) summary else "Saved release · cached edition",
        rows = canonicalReleaseTracksWithArtwork(rows, artworkUri, inheritedFromGroup = inheritedFromGroup),
        artworkUri = artworkUri,
    )
}

/**
 * If Room rejects a write or storage fails, a complete MusicBrainz lookup is
 * still useful to the current screen. It remains ephemeral, metadata-only
 * and never creates a false provider MediaAsset or persists an incomplete
 * edition. No fallback from ambiguous title/artist text is allowed.
 */
internal fun canonicalVerifiedLiveReleaseDetailPage(
    incoming: MusicBrainzCatalogReleaseDetail,
    artworkUri: String?,
): CanonicalCatalogDetailPage? {
    val proof = incoming.completeness
    if (!proof.countsExplicitAndExact || !proof.offsetsValid ||
        !proof.positionsExplicitUnique || incoming.tracks.isEmpty()
    ) return null
    val rows = incoming.tracks.map { track ->
        CanonicalCatalogSearchRow(
            musicBrainzId = track.recordingMbid,
            kind = CanonicalSearchKind.RECORDING,
            title = "${track.mediumNumber}.${track.trackNumber}  ${track.title}",
            subtitle = track.artistDisplay ?: incoming.artistDisplay,
            hasCatalogedAsset = false,
            fromOfflineCache = false,
            trackMbid = track.trackMbid,
            durationMs = track.durationMs,
        )
    }
    return CanonicalCatalogDetailPage(
        title = incoming.title,
        summary = listOfNotNull(incoming.country, incoming.releaseDate)
            .joinToString(" · "),
        artworkUri = artworkUri,
        rows = canonicalReleaseTracksWithArtwork(rows, artworkUri),
    )
}
