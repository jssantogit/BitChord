package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.Song

/**
 * Public Spotify embeds expose metadata but not a verified local rendition.
 * Keep every imported entry (including duplicate titles) as non-playable data.
 *
 * This is an entry marker for legacy LocalPlaylistStore serialization, NOT a
 * RecordingId, Spotify playable ID, source locator, or YouTube videoId.
 */
fun spotifyImportMetadataSong(
    playlistId: String,
    entryIndex: Int,
    track: SpotifyImportTrack,
): Song = Song(
    videoId = "${SPOTIFY_MISSING_PREFIX}import:$playlistId:$entryIndex",
    title = track.title,
    artist = track.artist,
    thumbnailUrl = null,
)
