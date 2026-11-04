package com.music.bitchord.data.spotify

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher

/**
 * Temporary YouTube-only boundary for historical Spotify matching.
 *
 * Do not use this for canonical discovery. Spotify metadata and local/Navidrome
 * assets must be resolved independently before this adapter can be deleted.
 */
internal object LegacySpotifyYoutubeMatcher {
    suspend fun matchTrack(track: SpotifyTrack): Song? {
        val query = listOf(track.title, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                album = track.album,
            ),
        ) ?: candidates.firstOrNull()
    }

    suspend fun matchImportTrack(track: SpotifyImportTrack): Song? {
        val query = "${track.title} ${track.artist}".trim()
        return YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.firstOrNull()
            ?.song
    }
}
