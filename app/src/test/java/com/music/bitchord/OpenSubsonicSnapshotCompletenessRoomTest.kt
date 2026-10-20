package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.MediaAssetEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCatalogSync
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class OpenSubsonicSnapshotCompletenessRoomTest {
    private val accountKey = "opensubsonic:home"
    private fun envelope(payload: String) = """{"subsonic-response":{"status":"ok",$payload}}"""
    private fun albums(vararg ids: String) =
        envelope(""""albumList2":{"album":[${ids.joinToString(",") { """{"id":"$it","name":"Album"}""" }}]}""")
    private fun album(id: String, songId: String = "song-old", count: Int = 1) =
        envelope(""""album":{"id":"$id","name":"Album","songCount":$count,"song":[{"id":"$songId","title":"Title","artist":"Artist"}]}""")

    private class Transport(
        val handler: suspend (String, Map<String, String>) -> String,
    ) : OpenSubsonicTransport {
        override suspend fun get(endpoint: String, parameters: Map<String, String>) =
            handler(endpoint, parameters)
    }

    private fun provider(transport: OpenSubsonicTransport) = OpenSubsonicProvider(
        config = OpenSubsonicAccountConfig("home", "https://localhost.test", OpenSubsonicAuth.ApiKey("fixture")),
        transport = transport,
        pageSize = 2,
    )

    private suspend fun seed(db: CanonicalCatalogDatabase) {
        val dao = db.catalogDao()
        dao.upsertRecording(RecordingEntity("r", "Original", "Artist", 1L))
        dao.upsertExternalIdentity(ExternalIdentityEntity(accountKey, "recording", "song-old", "r"))
        dao.upsertMediaAsset(MediaAssetEntity("asset-old", "r", accountKey, "song-old",
            "opensubsonic://asset/old", null, null, null, null))
    }

    @Test fun missingAlbumListEnvelopeNeverMeansValidEmptySnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val remote = provider(Transport { endpoint, _ ->
                assertEquals("getAlbumList2", endpoint)
                envelope(""""version":"1.16.1"""")
            })
            assertTrue(OpenSubsonicCatalogSync.sync(db, remote) is ProviderSyncResult.Failure)
            assertEquals("asset-old", db.catalogDao().mediaAsset(accountKey, "song-old")?.id)
            assertEquals("r", db.catalogDao().entityIdForExternalIdentity(accountKey, "recording", "song-old"))
        } finally { db.close() }
    }

    @Test fun inconsistentPaginationAndMissingAlbumNeverPruneOfflineAssets() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "c4-prune-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            val scenarios = listOf<suspend (String, Map<String, String>) -> String>(
                // An omitted album array is NOT evidence of an empty library.
                // Explicit "album":[] is the only safe empty page.
                { endpoint, _ -> if (endpoint == "getAlbumList2") envelope(""""albumList2":{}""") else error("unexpected getAlbum") },
                { endpoint, _ -> if (endpoint == "getAlbumList2") envelope(""""albumList2":{"album":null}""") else error("unexpected getAlbum") },
                { endpoint, _ -> if (endpoint == "getAlbumList2") albums("same", "same") else album("same") },
                { endpoint, args ->
                    if (endpoint == "getAlbumList2") {
                        if (args["offset"] == "0") albums("a", "b") else albums("b", "c")
                    } else album(args.getValue("id"))
                },
                { endpoint, _ -> if (endpoint == "getAlbumList2") albums("a") else envelope(""""version":"1.16.1"""") },
                { endpoint, _ -> if (endpoint == "getAlbumList2") albums("a") else album("WRONG") },
                { endpoint, _ -> if (endpoint == "getAlbumList2") albums("a") else album("a", count = 3) },
            )
            for (scenario in scenarios) {
                val result = OpenSubsonicCatalogSync.sync(db, provider(Transport(scenario)))
                assertTrue("Incomplete snapshot accepted: $result", result is ProviderSyncResult.Failure)
                assertEquals("asset-old", db.catalogDao().mediaAsset(accountKey, "song-old")?.id)
                assertEquals("Original", db.catalogDao().recordingById("r")?.title)
            }
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            assertEquals("asset-old", db.catalogDao().mediaAsset(accountKey, "song-old")?.id)
            assertEquals("r", db.catalogDao().entityIdForExternalIdentity(accountKey, "recording", "song-old"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun cancellationAndNetworkFailuresPreserveLastGoodSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val offline = provider(Transport { _, _ -> throw IOException("offline") })
            assertTrue(OpenSubsonicCatalogSync.sync(db, offline) is ProviderSyncResult.Failure)
            val cancelled = provider(Transport { _, _ -> throw CancellationException("cancelled") })
            try {
                OpenSubsonicCatalogSync.sync(db, cancelled)
                throw AssertionError("Cancellation was swallowed")
            } catch (_: CancellationException) {}
            assertEquals("asset-old", db.catalogDao().mediaAsset(accountKey, "song-old")?.id)
        } finally { db.close() }
    }

    @Test fun explicitValidEmptyPageMayPruneOnlyProviderAssetNotIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            seed(db)
            val remote = provider(Transport { _, _ -> albums() })
            val result = OpenSubsonicCatalogSync.sync(db, remote)
            assertTrue(result is ProviderSyncResult.Success)
            assertEquals(null, db.catalogDao().mediaAsset(accountKey, "song-old"))
            assertEquals("r", db.catalogDao().entityIdForExternalIdentity(accountKey, "recording", "song-old"))
        } finally { db.close() }
    }
}
