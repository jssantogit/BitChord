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

/**
 * A bounded playback snapshot is not a complete listening history. Only
 * entries up to the current cursor could have actually been heard; future
 * queue members and unresolved historical video IDs are not recent audio.
 */
internal fun canonicalAutoRecentSongs(
    songs: List<Song>,
    currentIndex: Int,
    verifiedDownloadUri: (String) -> String?,
): List<Song> {
    if (songs.isEmpty() || currentIndex < 0) return emptyList()
    fun verified(uri: String?): Boolean = uri != null && (
        uri.startsWith("content://", ignoreCase = true) ||
            uri.startsWith("file://", ignoreCase = true) ||
            CanonicalRemotePlaybackUri.decode(uri)
                ?.providerKey?.startsWith("opensubsonic:") == true
        )
    return songs.take(currentIndex.coerceAtMost(songs.lastIndex) + 1)
        .asReversed()
        .mapNotNull { song ->
            val uri = sequenceOf(song.localUri, verifiedDownloadUri(song.videoId), song.videoId)
                .firstOrNull(::verified) ?: return@mapNotNull null
            song.copy(localUri = uri)
        }
        .distinctBy(Song::videoId)
}

/** Music already in the device's local/downloading folders and authenticated
 * canonical servers, never a public catalog result without playable audio. */
internal fun canonicalAutoQuickPicks(
    local: List<Song>,
    downloads: List<Song>,
    remote: List<Song>,
    excludedIds: Set<String>,
): List<Song> = (local + downloads + remote)
    .filter { it.videoId !in excludedIds }
    .distinctBy(Song::videoId)
    .take(150)
