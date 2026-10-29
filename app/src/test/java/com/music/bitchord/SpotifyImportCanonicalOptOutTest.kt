package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyImportTrack
import com.music.bitchord.data.spotify.SpotifyImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpotifyImportCanonicalOptOutTest {
    @Test fun blockedMatcherKeepsUnmatchedSpotifyTracksWithoutYoutubeLookup() = runBlocking {
        val tracks = listOf(
            SpotifyImportTrack("Magnetic", "ILLIT"),
            SpotifyImportTrack("Supernova", "aespa"),
        )
        val callbacks = AtomicInteger()
        val (songs, unmatched) = SpotifyImporter.resolveToSongs(
            tracks = tracks,
            onProgress = { _, total ->
                assertEquals(2, total)
                callbacks.incrementAndGet()
            },
            allowYoutubeMatching = { false },
        )
        assertTrue(songs.isEmpty())
        assertEquals(tracks, unmatched)
        assertEquals(2, callbacks.get())
    }

    @Test fun emptyPlaylistHasNoNetworkOrSyntheticRows() = runBlocking {
        val (songs, unmatched) = SpotifyImporter.resolveToSongs(
            tracks = emptyList(),
            onProgress = { _, _ -> error("unexpected progress") },
            allowYoutubeMatching = { false },
        )
        assertTrue(songs.isEmpty())
        assertTrue(unmatched.isEmpty())
    }
}
