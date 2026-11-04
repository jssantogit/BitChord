package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalRemotePlaybackUri

/**
 * Historical LocalPlaylistStore rows retain their original videoId as a
 * compatibility alias. Only a verified on-device URI or a fully typed
 * canonical remote playback request can enable play/queue actions.
 */
internal fun legacyPlaylistPlayableSong(song: Song, verifiedDownloadedUri: String? = null): Song? {
    fun safe(uri: String?): Boolean = uri != null && (
        uri.startsWith("content://", ignoreCase = true) ||
            uri.startsWith("file://", ignoreCase = true) ||
            CanonicalRemotePlaybackUri.decode(uri)
                ?.providerKey?.startsWith("opensubsonic:") == true
        )
    val uri = sequenceOf(song.localUri, verifiedDownloadedUri, song.videoId)
        .firstOrNull(::safe) ?: return null
    return song.copy(localUri = uri)
}

/** Preserve duplicate playlist entries and original order; drop only unavailable
 * entries from playback, never from the stored or visible collection. */
internal fun eligibleHistoricalPlaylistSongs(
    songs: List<Song>,
    verifiedDownloadedUri: (Song) -> String?,
): List<Song> = songs.mapNotNull { song ->
    legacyPlaylistPlayableSong(song, verifiedDownloadedUri(song))
}
