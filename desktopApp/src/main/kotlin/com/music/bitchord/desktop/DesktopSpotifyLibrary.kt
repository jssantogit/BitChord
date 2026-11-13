package com.music.bitchord.desktop

import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The Library row that opens the Spotify account's playlists; the phone's browse id. */
internal const val SPOTIFY_LIBRARY_ID = "app:spotify"

/** The Library grid's title for those playlists, which is also how the grid is told apart. */
internal const val SPOTIFY_SHELF = "Spotify"

/** A card on the Spotify grid, opening a [DesktopCollection] of its tracks. */
internal fun SpotifyPlaylist.toShelfItem() = ShelfItem(
    title = name,
    subtitle = owner ?: SPOTIFY_SHELF,
    thumbnailUrl = imageUrl,
    videoId = null,
    browseId = SPOTIFY_PAGE_PREFIX + id,
)

/** A Spotify account row without an independently verified playable asset. */
internal fun SpotifyTrack.asMetadataSong() = Song(
    videoId = SPOTIFY_MISSING_PREFIX + id,
    title = title,
    artist = artist,
    thumbnailUrl = imageUrl,
    durationText = durationMs.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) },
    albumName = album,
)

/**
 * Fills the Spotify playlist page with source-owned metadata, preserving
 * ordering and duplicates. Desktop must not query YouTube implicitly;
 * no row is marked playable without a verified MediaAsset. [update]
 * only applies changes while the page remains open.
 */
internal suspend fun loadSpotifyPlaylist(
    playlistId: String,
    update: ((DesktopCollection) -> DesktopCollection) -> Unit,
) = coroutineScope {
    launch {
        // The library card only had a thumbnail.
        runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull()
            ?.let { cover -> update { it.copy(thumbnailUrl = cover) } }
    }
    val tracks = SpotifyLibrary.tracks(playlistId) { soFar ->
        update {
            it.copy(songs = soFar.map(SpotifyTrack::asMetadataSong), loading = false)
        }
    }
    check(tracks.isNotEmpty()) { "This Spotify playlist has no tracks." }
}
