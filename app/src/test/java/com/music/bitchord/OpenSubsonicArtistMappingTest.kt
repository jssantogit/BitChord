package com.music.bitchord

import com.music.bitchord.data.catalog.provider.ProviderArtist
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicArtistMappingTest {

    @Test
    fun providerPreservesOrderedStructuredArtistIdentityAndMusicBrainzEvidence() = runBlocking {
        val transport = FixtureTransport { endpoint, _ ->
            when (endpoint) {
                "getAlbumList2" -> albumListResponse()
                "getAlbum" -> albumResponseWithOrderedArtists()
                else -> error("Unexpected endpoint $endpoint")
            }
        }
        val provider = OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                accountId = "home",
                serverUrl = "https://music.example.test",
                auth = OpenSubsonicAuth.ApiKey("secret"),
            ),
            transport = transport,
        )

        val track = provider.librarySnapshot().tracks.single()

        assertEquals(
            listOf(
                ProviderArtist("artist-a", "Artist A", "mb-artist-a"),
                ProviderArtist("artist-b", "Artist B", "mb-artist-b"),
            ),
            track.artists,
        )
        assertEquals(
            listOf(
                ProviderArtist("artist-b", "Artist B", "mb-artist-b"),
                ProviderArtist("artist-a", "Artist A", "mb-artist-a"),
            ),
            track.album?.artists,
        )
    }

    @Test
    fun legacySingleArtistIdIsStructuredEvidenceButDisplayTextIsNeverSplit() = runBlocking {
        val transport = FixtureTransport { endpoint, _ ->
            when (endpoint) {
                "getAlbumList2" -> albumListResponse()
                "getAlbum" -> albumResponseWithoutStructuredArtists()
                else -> error("Unexpected endpoint $endpoint")
            }
        }
        val provider = OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                accountId = "home",
                serverUrl = "https://music.example.test",
                auth = OpenSubsonicAuth.ApiKey("secret"),
            ),
            transport = transport,
        )

        val track = provider.librarySnapshot().tracks.single()

        assertEquals("Artist A & Artist B feat. C", track.artistDisplay)
        assertEquals(
            listOf(ProviderArtist("single-provider-artist", "Artist A & Artist B feat. C", null)),
            track.artists,
        )
        assertTrue(track.artists.none { it.name == "Artist A" || it.name == "Artist B" || it.name == "C" })
    }

    private fun albumListResponse() = """
        {
          "subsonic-response": {
            "status": "ok",
            "version": "1.16.1",
            "albumList2": {
              "album": [{"id":"album-1","name":"Album"}]
            }
          }
        }
    """.trimIndent()

    private fun albumResponseWithOrderedArtists() = """
        {
          "subsonic-response": {
            "status": "ok",
            "version": "1.16.1",
            "album": {
              "id": "album-1",
              "name": "Album",
              "artist": "Artist B & Artist A",
              "artistId": "artist-b",
              "artists": [
                {"id":"artist-b","name":"Artist B","musicBrainzId":"mb-artist-b"},
                {"id":"artist-a","name":"Artist A","musicBrainzId":"mb-artist-a"}
              ],
              "song": [{
                "id": "song-1",
                "title": "Song",
                "artist": "Artist A & Artist B",
                "artistId": "artist-a",
                "artists": [
                  {"id":"artist-a","name":"Artist A","musicBrainzId":"mb-artist-a"},
                  {"id":"artist-b","name":"Artist B","musicBrainzId":"mb-artist-b"}
                ]
              }]
            }
          }
        }
    """.trimIndent()

    private fun albumResponseWithoutStructuredArtists() = """
        {
          "subsonic-response": {
            "status": "ok",
            "version": "1.16.1",
            "album": {
              "id": "album-1",
              "name": "Album",
              "artist": "Artist A & Artist B feat. C",
              "artistId": "single-provider-artist",
              "song": [{
                "id": "song-1",
                "title": "Song",
                "artist": "Artist A & Artist B feat. C",
                "artistId": "single-provider-artist"
              }]
            }
          }
        }
    """.trimIndent()

    private class FixtureTransport(
        private val responder: (String, Map<String, String>) -> String,
    ) : OpenSubsonicTransport {
        override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
            responder(endpoint, parameters)
    }
}
