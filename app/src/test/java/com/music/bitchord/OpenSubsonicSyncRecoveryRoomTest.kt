package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicCatalogSync
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
class OpenSubsonicSyncRecoveryRoomTest {
    private fun provider(
        title: String,
        gate: CompletableDeferred<Unit>? = null,
        entered: CompletableDeferred<Unit>? = null,
    ): OpenSubsonicProvider = OpenSubsonicProvider(
        config = OpenSubsonicAccountConfig("home", "https://music.example.test", OpenSubsonicAuth.ApiKey("secret")),
        transport = object : OpenSubsonicTransport {
            override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
                when (endpoint) {
                    "getAlbumList2" -> """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"album","name":"Album"}]}}}"""
                    "getAlbum" -> {
                        entered?.complete(Unit)
                        gate?.await()
                        """{"subsonic-response":{"status":"ok","album":{
                            "id":"album","name":"Album","songCount":1,
                            "song":[{"id":"song","title":"$title","artist":"Artist"}]
                        }}}"""
                    }
                    else -> error("Unexpected endpoint")
                }
        },
    )
    private fun ids() = AtomicInteger().let { counter ->
        { "canonical-${counter.incrementAndGet()}" }
    }

    @Test fun lateOldResponseCannotRewindNewerSuccessfulSnapshotOrPruneAssets() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "c8-concurrency-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val oldGate = CompletableDeferred<Unit>()
            val oldEntered = CompletableDeferred<Unit>()
            val slow = async {
                OpenSubsonicCatalogSync.sync(db, provider("OLD", oldGate, oldEntered),
                    idFactory = { error("Stale read must not allocate IDs") })
            }
            oldEntered.await()
            val fresh = OpenSubsonicCatalogSync.sync(db, provider("NEW"), idFactory = ids())
            assertTrue(fresh is ProviderSyncResult.Success)
            val stableAsset = db.catalogDao().mediaAsset("opensubsonic:home", "song")
            assertEquals("NEW", db.catalogDao().recordingById(requireNotNull(stableAsset).recordingId)?.title)
            oldGate.complete(Unit)
            val stale = slow.await()
            assertTrue("Late snapshot must not apply: $stale", stale is ProviderSyncResult.Failure)
            assertEquals(stableAsset, db.catalogDao().mediaAsset("opensubsonic:home", "song"))
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            assertEquals("NEW", db.catalogDao().recordingById(stableAsset.recordingId)?.title)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun accountEditOrForgetDuringNetworkReadInvalidatesOldResponse() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java).allowMainThreadQueries().build()
        try {
            val first = OpenSubsonicCatalogSync.sync(db, provider("EXISTING"), idFactory = ids())
            assertTrue(first is ProviderSyncResult.Success)
            val before = requireNotNull(db.catalogDao().mediaAsset("opensubsonic:home", "song"))
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var accountIsCurrent = true
            val old = async {
                OpenSubsonicCatalogSync.sync(
                    database = db,
                    provider = provider("STALE", gate, entered),
                    idFactory = { error("Stale account should not write") },
                    accountIsCurrent = { accountIsCurrent },
                )
            }
            entered.await()
            // Equivalent to changing server, credential or forgetting account
            // while an authenticated remote request is still in flight.
            accountIsCurrent = false
            gate.complete(Unit)
            assertTrue(old.await() is ProviderSyncResult.Failure)
            assertEquals(before, db.catalogDao().mediaAsset("opensubsonic:home", "song"))
            assertEquals("EXISTING", db.catalogDao().recordingById(before.recordingId)?.title)
        } finally { db.close() }
    }

    @Test fun replayOfUnchangedAccountRetainsStableCanonicalIdsAfterRestart() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "c8-replay-" + System.nanoTime() + ".db"
        var db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider("ORIGINAL"), idFactory = ids())
                is ProviderSyncResult.Success)
            val before = db.catalogDao().mediaAsset("opensubsonic:home", "song")
            db.close()
            db = Room.databaseBuilder(context, CanonicalCatalogDatabase::class.java, name)
                .allowMainThreadQueries().build()
            assertTrue(OpenSubsonicCatalogSync.sync(db, provider("ORIGINAL"),
                idFactory = { error("Replay allocated an ID") }) is ProviderSyncResult.Success)
            assertEquals(before, db.catalogDao().mediaAsset("opensubsonic:home", "song"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
