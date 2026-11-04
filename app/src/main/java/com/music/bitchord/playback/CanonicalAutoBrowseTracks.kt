package com.music.bitchord.playback

import com.music.bitchord.data.catalog.CanonicalBrowseAssetRow
import com.music.bitchord.data.model.Song

/**
 * Provider-neutral MediaLibraryService projection for remote browse/search.
 *
 * Only an asset that the Media3 data source knows how to revalidate may be
 * advertised as playable. A MusicBrainz Recording alone is not stream audio.
 * No server URL, secret, or native track key crosses the session boundary.
 */
internal fun canonicalAutoSong(row: CanonicalBrowseAssetRow): Song? {
    if (!row.providerKey.startsWith("opensubsonic:") ||
        row.recordingId.isBlank() || row.mediaAssetId.isBlank() ||
        row.title.isBlank()
    ) return null
    val logicalUri = CanonicalRemotePlaybackUri.encode(
        CanonicalRemotePlaybackRequest(
            recordingId = row.recordingId,
            mediaAssetId = row.mediaAssetId,
            providerKey = row.providerKey,
        ),
    )
    val duration = row.durationMs?.takeIf { it > 0L }?.let {
        "%d:%02d".format(it / 60000L, it / 1000L % 60L)
    }
    return Song(
        videoId = logicalUri,
        title = row.title,
        artist = row.artistDisplay,
        thumbnailUrl = row.artworkUri,
        durationText = duration,
        localUri = logicalUri,
    )
}

/** A recording is listed once; select its deterministic provider-scoped asset. */
internal fun canonicalAutoSongs(rows: List<CanonicalBrowseAssetRow>): List<Song> =
    rows.groupBy(CanonicalBrowseAssetRow::recordingId).values.mapNotNull { options ->
        options.sortedWith(
            compareBy<CanonicalBrowseAssetRow> { it.providerKey }
                .thenBy { it.mediaAssetId },
        ).firstNotNullOfOrNull(::canonicalAutoSong)
    }
