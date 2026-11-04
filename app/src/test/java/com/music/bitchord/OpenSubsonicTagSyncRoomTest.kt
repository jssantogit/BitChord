package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCatalogSync
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/** Real provider response and real Room transaction must agree on tag provenance. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class OpenSubsonicTagSyncRoomTest {
    @Test fun resyncReplacesTagValuesAndPruningCascadesOnlyCurrentAssets() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            var untagged = false
            val transport = object : OpenSubsonicTransport {
                override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
                    when (endpoint) {
                        "getAlbumList2" -> """
                            {"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"a"}]}}}
                        """.trimIndent()
                        "getAlbum" -> if (!untagged) """
                            {"subsonic-response":{"status":"ok","album":{
                                "id":"a","songCount":1,"song":[
                                    {"id":"1","title":"A","artist":"B",
                                     "genres":[{"name":"Trip Hop"}],"moods":["Calm"],"bpm":124,
                                     "path":"Artist/Album/01.flac",
                                     "contributors":[{"role":"composer","artist":{"id":"1","name":"Alice"}}]}
                                ]
                            }}}
                        """.trimIndent() else """
                            {"subsonic-response":{"status":"ok","album":{
                                "id":"a","songCount":1,"song":[
                                    {"id":"1","title":"A","artist":"B","genres":[],"moods":[],"bpm":0,
                                     "path":"Artist/Album/01.flac","contributors":[]}
                                ]
                            }}}
                        """.trimIndent()
                        else -> error("unexpected endpoint")
                    }
            }
            val provider = OpenSubsonicProvider(
                config = OpenSubsonicAccountConfig(
                    "home", "https://example.test", OpenSubsonicAuth.ApiKey("fixture"),
                ),
                transport = transport,
            )
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider) is ProviderSyncResult.Success)
            val dao = db.catalogDao()
            val ids = dao.availableRecordingIdsForProvider("opensubsonic:home")
            assertEquals(1, ids.size)
            var tags = dao.providerScopedTagRows("opensubsonic:home", ids).single()
            assertEquals("[\"Trip Hop\"]", tags.genresJson)
            assertEquals("[\"Calm\"]", tags.moodsJson)
            assertEquals("[124.0]", tags.bpmsJson)
            assertEquals("[\"Alice\"]", tags.composersJson)
            assertEquals("Artist/Album/01.flac", tags.assetPath)
            untagged = true
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider) is ProviderSyncResult.Success)
            tags = dao.providerScopedTagRows("opensubsonic:home", ids).single()
            assertEquals("[]", tags.genresJson)
            assertEquals("[]", tags.moodsJson)
            assertEquals("[]", tags.bpmsJson)
            assertEquals("[]", tags.composersJson)
            assertEquals("Artist/Album/01.flac", tags.assetPath)
            dao.deleteAllMediaAssets("opensubsonic:home")
            assertTrue(dao.providerScopedTagRows("opensubsonic:home", ids).isEmpty())
        } finally {
            db.close()
        }
    }
}
