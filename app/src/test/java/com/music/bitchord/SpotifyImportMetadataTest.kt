package com.music.bitchord

import com.music.bitchord.data.model.isMatchMissing
import com.music.bitchord.data.spotify.SpotifyImportTrack
import com.music.bitchord.data.spotify.spotifyImportMetadataSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyImportMetadataTest {
    @Test fun publicPlaylistImportKeepsEveryEntryWithoutYoutubeOrFakeRecordingId() {
        val tracks = listOf(
            SpotifyImportTrack("Supernova", "aespa"),
            SpotifyImportTrack("Supernova", "aespa"),
            SpotifyImportTrack("Magnetic", "ILLIT"),
        )
        val songs = tracks.mapIndexed { index, track ->
            spotifyImportMetadataSong("playlist-public", index, track)
        }
        assertEquals(tracks.map { it.title }, songs.map { it.title })
        assertEquals(3, songs.map { it.videoId }.distinct().size)
        assertTrue(songs.all { it.isMatchMissing })
        assertTrue(songs.all { it.localUri == null })
        assertEquals("aespa", songs.first().artist)
    }

    @Test fun placeholderIsNotAProviderNativePlaybackLocator() {
        val song = spotifyImportMetadataSong(
            "playlist-public", 0, SpotifyImportTrack("Unknown", "Artist"),
        )
        assertTrue(song.videoId.startsWith("sp-miss:import:"))
        assertNull(song.localPath)
    }
}
