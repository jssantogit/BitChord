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
