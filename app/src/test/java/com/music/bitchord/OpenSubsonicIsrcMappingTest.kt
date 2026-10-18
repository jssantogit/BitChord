package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSubsonicIsrcMappingTest {

    @Test
    fun structuredIsrcArrayIsNormalizedFilteredAndDeduplicated() = runBlocking {
        val provider = provider(
            songJson = """
                {
                  "id":"song-1",
                  "title":"Song",
                  "artist":"Artist",
                  "isrc":[
                    "US-S1Z-99-00001",
                    "uss1z9900001",
                    "not-an-isrc",
                    "GB-ABC-24-00001"
                  ]
                }
            """.trimIndent(),
        )

        val track = provider.librarySnapshot().tracks.single()

        assertEquals(
            listOf("USS1Z9900001", "GBABC2400001"),
            track.isrcs,
        )
    }

    @Test
    fun invalidIsrcDoesNotFailLibrarySync() = runBlocking {
        val provider = provider(
            songJson = """
                {
                  "id":"song-invalid",
                  "title":"Song",
                  "artist":"Artist",
                  "isrc":["garbage","US-ABC-XX-00001"]
                }
            """.trimIndent(),
        )

        val snapshot = provider.librarySnapshot()

        assertEquals(1, snapshot.tracks.size)
        assertEquals(emptyList<String>(), snapshot.tracks.single().isrcs)
    }

    @Test
    fun absentIsrcPreservesExistingProviderBehavior() = runBlocking {
        val provider = provider(
            songJson = """
                {
                  "id":"song-no-isrc",
                  "title":"Song",
                  "artist":"Artist"
                }
            """.trimIndent(),
        )

        val track = provider.librarySnapshot().tracks.single()

        assertEquals("song-no-isrc", track.externalTrackId)
        assertEquals(emptyList<String>(), track.isrcs)
    }

    private fun provider(songJson: String): OpenSubsonicProvider {
        val transport = FixtureTransport { endpoint, _ ->
            when (endpoint) {
                "getAlbumList2" -> """
                    {
                      "subsonic-response": {
                        "status":"ok",
                        "version":"1.16.1",
                        "albumList2":{"album":[{"id":"album-1","name":"Album"}]}
                      }
                    }
                """.trimIndent()
                "getAlbum" -> """
                    {
                      "subsonic-response": {
                        "status":"ok",
                        "version":"1.16.1",
                        "album": {
                          "id":"album-1",
                          "name":"Album",
                          "artist":"Artist",
                          "song":[$songJson]
                        }
                      }
                    }
                """.trimIndent()
                else -> error("Unexpected endpoint $endpoint")
            }
        }
        return OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                accountId = "home",
                serverUrl = "https://music.example.test",
                auth = OpenSubsonicAuth.ApiKey("secret"),
            ),
            transport = transport,
        )
    }

    private class FixtureTransport(
        private val responder: (String, Map<String, String>) -> String,
    ) : OpenSubsonicTransport {
        override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
            responder(endpoint, parameters)
    }
}
