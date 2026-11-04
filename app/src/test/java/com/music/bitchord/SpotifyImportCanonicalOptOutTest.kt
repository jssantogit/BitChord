package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyImportTrack
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.model.Song
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

    @Test fun injectedResolverMatchesWithoutConsultingYoutube() = runBlocking {
        val tracks = listOf(
            SpotifyImportTrack("Supernova", "aespa"),
            SpotifyImportTrack("Unknown", "Unknown"),
        )
        val queries = mutableListOf<String>()
        val (songs, unmatched) = SpotifyImporter.resolveToSongsWithResolver(
            tracks = tracks,
            onProgress = { _, _ -> },
            allowMatching = { true },
            resolveTrack = { track ->
                synchronized(queries) { queries.add(track.title) }
                if (track.title == "Supernova") Song(
                    videoId = "local:sample",
                    title = track.title,
                    artist = track.artist,
                ) else null
            },
        )
        assertEquals(listOf("Supernova"), songs.map { it.title })
        assertEquals(listOf("Unknown"), unmatched.map { it.title })
        assertEquals(setOf("Supernova", "Unknown"), queries.toSet())
    }

    @Test fun blockedGateNeverCallsInjectedResolver() = runBlocking {
        val tracks = listOf(SpotifyImportTrack("Magnetic", "ILLIT"))
        val (songs, unmatched) = SpotifyImporter.resolveToSongsWithResolver(
            tracks = tracks,
            onProgress = { _, _ -> },
            allowMatching = { false },
            resolveTrack = { error("source policy must block resolution") },
        )
        assertTrue(songs.isEmpty())
        assertEquals(tracks, unmatched)
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
