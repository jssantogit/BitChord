package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wire contract: missing optional tags are unknown, [] is known untagged. */
class OpenSubsonicTagMappingTest {
    @Test fun optionalGenresAndMoodsPreserveEmptyMultiValueAndUnknownDistinctions() = runBlocking {
        val transport = object : OpenSubsonicTransport {
            override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
                when (endpoint) {
                    "getAlbumList2" -> """
                        {"subsonic-response":{"status":"ok","albumList2":{"album":[
                            {"id":"album-1","name":"Test"}
                        ]}}}
                    """.trimIndent()
                    "getAlbum" -> """
                        {"subsonic-response":{"status":"ok","album":{
                            "id":"album-1","name":"Test","songCount":5,"song":[
                                {"id":"song-1","title":"Tagged","genres":[
                                    {"name":"Dream Pop"},{"name":"K-Pop"}],
                                    "moods":["Calm","Bright"],"bpm":134,
                                    "path":"Artist/Album/01.flac",
                                    "displayComposer":"Alice",
                                    "contributors":[{"role":"composer","artist":{"id":"comp-2","name":"Bob"}},
                                                    {"role":"performer","artist":{"id":"p-1","name":"Singer"}}]},
                                {"id":"song-2","title":"Untagged","genres":[],"moods":[],"bpm":0,
                                 "path":"Artist/Album/02.flac","contributors":[]},
                                {"id":"song-3","title":"Unsupported"},
                                {"id":"song-4","title":"Legacy","genre":"Rock"},
                                {"id":"song-5","title":"Malformed composer",
                                 "displayComposer":"Alice",
                                 "contributors":[{"role":"composer"}]}
                            ]
                        }}}
                    """.trimIndent()
                    else -> error("Unexpected endpoint")
                }
        }
        val provider = OpenSubsonicProvider(
            config = OpenSubsonicAccountConfig(
                "tags", "https://example.test", OpenSubsonicAuth.ApiKey("fixture"),
            ),
            transport = transport,
        )
        val tracks = provider.librarySnapshot().tracks.associateBy { it.externalTrackId }
        assertEquals(listOf("Dream Pop", "K-Pop"), tracks.getValue("song-1").genres)
        assertEquals(listOf("Calm", "Bright"), tracks.getValue("song-1").moods)
        assertEquals(134, tracks.getValue("song-1").bpm)
        assertEquals("Artist/Album/01.flac", tracks.getValue("song-1").path)
        assertEquals(listOf("Bob", "Alice"), tracks.getValue("song-1").composers)
        assertEquals(emptyList<String>(), tracks.getValue("song-2").composers)
        assertNull(tracks.getValue("song-3").composers)
        assertNull("malformed credits cannot prove complete composer evidence",
            tracks.getValue("song-5").composers)
        assertEquals(0, tracks.getValue("song-2").bpm)
        assertNull(tracks.getValue("song-3").bpm)
        assertEquals(emptyList<String>(), tracks.getValue("song-2").genres)
        assertEquals(emptyList<String>(), tracks.getValue("song-2").moods)
        assertNull(tracks.getValue("song-3").genres)
        assertNull(tracks.getValue("song-3").moods)
        assertEquals(listOf("Rock"), tracks.getValue("song-4").genres)
        assertNull(tracks.getValue("song-4").moods)
    }
}
