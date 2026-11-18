package com.music.bitchord.data.canvas

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import com.music.bitchord.data.providers.ArtworkProbePolicyGuard
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualStillFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cancellationClosesStreamingVisualStillHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse()
                .setHeadersDelay(30, TimeUnit.SECONDS)
                .setBody("delayed image bytes"))
            server.start()
            val call = OkHttpClient().newCall(
                Request.Builder().url(server.url("/still.jpg")).build())
            val target = File(temp.root, "cancelled-still.part")
            val pending = async(Dispatchers.IO) { downloadVisualStill(call, target) }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            pending.cancel()
            withTimeout(5_000) { pending.join() }
            assertTrue(call.isCanceled())
            target.delete()
            Unit
        }
    }

    @Test fun revokedArtistArtStopsPendingOfflineImageWithoutPublishingPartialFile() = runBlocking {
        val folder = temp.newFolder("revoked-art")
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val key = ProviderKey("apple_artist_art")
        fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key, setOf(ProviderCapability.ARTIST_ARTWORK), ProviderReadiness.READY)),
            choices = mapOf(ProviderCapability.ARTIST_ARTWORK to listOf(
                ProviderChoice(key, enabled, 0))),
        )
        val gate = ProviderPolicyGate(policy(true))
        val store = VisualStillFileStore(folder, downloader = { _, output ->
            output.writeBytes(byteArrayOf(1, 2, 3))
            entered.complete(Unit)
            try { awaitCancellation() }
            finally { cancelled.complete(Unit) }
        })
        val pending = async(Dispatchers.IO) {
            ArtworkProbePolicyGuard { gate }.probe(
                ProviderCapability.ARTIST_ARTWORK, key,
            ) { store.ensure(VisualSurface.ARTIST, "revoked-artist", "https://images.example/portrait.jpg") }
        }
        withTimeout(5_000) { entered.await() }
        gate.replace(policy(false))
        withTimeout(5_000) { cancelled.await() }
        assertNull(withTimeout(5_000) { pending.await() })
        assertNull(store.cached(VisualSurface.ARTIST, "revoked-artist"))
        assertFalse(folder.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun downloadedStillSurvivesNewStoreInstanceAndOfflineLookup() = runBlocking {
        val folder = temp.newFolder("visuals")
        var networkRequests = 0
        val save = VisualStillFileStore(folder, downloader = { _, into ->
            networkRequests++
            into.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            true
        })
        val remote = "https://images.example/artist.jpg"
        val first = save.ensure(VisualSurface.ARTIST, "AtHeart", remote)
        assertTrue(first != null && first.exists())
        val coldRestart = VisualStillFileStore(folder, downloader = { _, _ ->
            networkRequests++
            false
        })
        assertEquals(first!!.absolutePath,
            coldRestart.cached(VisualSurface.ARTIST, "AtHeart")?.absolutePath)
        assertEquals(1, networkRequests)
    }

    @Test fun incompleteOrMaliciousDownloadNeverBecomesAnOfflineImage() = runBlocking {
        val folder = temp.newFolder("atomic")
        val save = VisualStillFileStore(folder, downloader = { _, output ->
            output.writeBytes(byteArrayOf(1, 2))
            false
        })
        assertNull(save.ensure(VisualSurface.ARTIST, "ILLIT",
            "https://images.example/fail.jpg"))
        assertNull(save.cached(VisualSurface.ARTIST, "ILLIT"))
        assertFalse(folder.listFiles().orEmpty().any { it.extension == "part" })
        assertNull(save.ensure(VisualSurface.ARTIST, "ILLIT",
            "file:///data/data/private.jpg"))
    }

    @Test fun configurableQuotaRemovesOldestImagesWithoutLosingRecentOnes() = runBlocking {
        val dir = temp.newFolder("limited")
        var now = 100L
        val store = VisualStillFileStore(dir, downloader = { _, file ->
            file.writeBytes(ByteArray(1024) { 7 })
            file.setLastModified(now++)
            true
        }, maxBytes = { 2048L })
        store.ensure(VisualSurface.ARTIST, "a", "https://images.example/a.jpg")
        store.ensure(VisualSurface.ARTIST, "b", "https://images.example/b.jpg")
        store.ensure(VisualSurface.ARTIST, "c", "https://images.example/c.jpg")
        assertNull(store.cached(VisualSurface.ARTIST, "a"))
        assertTrue(store.cached(VisualSurface.ARTIST, "b") != null)
        assertTrue(store.cached(VisualSurface.ARTIST, "c") != null)
        assertTrue(store.bytesOnDisk() <= 2048L)
    }

    @Test fun cacheKeysCannotCollideAcrossArtistAlbumAndPlayback() = runBlocking {
        val folder = temp.newFolder("independent")
        val save = VisualStillFileStore(folder, downloader = { _, f ->
            f.writeBytes(byteArrayOf(1, 2, 3)); true
        })
        save.ensure(VisualSurface.ALBUM, "Say It", "https://img.example/one.jpg")
        assertNull(save.cached(VisualSurface.ARTIST, "Say It"))
        assertNull(save.cached(VisualSurface.NOW_PLAYING, "Say It"))
        assertTrue(save.cached(VisualSurface.ALBUM, "Say It")!!.exists())
        save.clear()
        assertNull(save.cached(VisualSurface.ALBUM, "Say It"))
    }
}
