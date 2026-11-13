package com.music.bitchord.desktop

import com.music.bitchord.data.model.isMatchMissing
import com.music.bitchord.data.spotify.SpotifyTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpotifyPlaylistMetadataTest {
    @Test fun publicSpotifyRowsAreMetadataNotYoutubeRenditions() {
        val track = SpotifyTrack(
            id = "spotify-123",
            title = "Supernova",
            artist = "aespa",
            album = "Armageddon",
            durationMs = 179000,
            imageUrl = null,
        )
        val song = track.asMetadataSong()
        assertTrue(song.isMatchMissing)
        assertEquals("Supernova", song.title)
        assertEquals("aespa", song.artist)
        assertEquals(null, song.localUri)
    }
}
