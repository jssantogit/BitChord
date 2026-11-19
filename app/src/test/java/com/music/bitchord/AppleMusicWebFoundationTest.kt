package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebBatch
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebBatchResult
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCacheContract
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCompleteness
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebHttpException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AppleMusicWebFoundationTest {
    @Test fun defaultInventoryIsBlockedEvenWhenUserExplicitlyEnablesBothCapabilities() {
        val inventory = ProviderCapabilityRegistry.inventory()
        val rows = inventory.filter { it.key == BuiltInProviderKeys.APPLE_MUSIC_WEB }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.readiness == ProviderReadiness.BLOCKED })
        val choices = rows.groupBy { it.capability }.mapValues { (_, items) ->
            listOf(ProviderChoice(items.first().key, enabled = true, order = 0))
        }
        val policy = ProviderCapabilityPolicy(ProviderCapabilityRegistry.descriptorsFrom(inventory), choices)
        val gate = ProviderPolicyGate(policy)
        assertNull(gate.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        assertNull(gate.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.APPLE_MUSIC_WEB))
    }

    @Test fun cacheKeysSeparateMarketLanguageCapabilityAndNeverContainCredentials() {
        val path = "/v1/catalog/br/search"
        val args = mapOf("term" to "Música", "types" to "songs")
        val search = ProviderCapability.CATALOG_SEARCH
        val key = AppleMusicWebCacheContract.key(search, "br", "pt-BR", path, args)
        assertNotNull(key)
        assertEquals(key, AppleMusicWebCacheContract.key(search, "br", "pt-BR", path, args.entries.reversed().associate { it.key to it.value }))
        assertNotEquals(key, AppleMusicWebCacheContract.key(search, "br", "en-US", path, args))
        assertNotEquals(key, AppleMusicWebCacheContract.key(ProviderCapability.EDITORIAL_DISCOGRAPHY, "br", "pt-BR", path, args))
        assertNotEquals(key, AppleMusicWebCacheContract.key(search, "us", "pt-BR", "/v1/catalog/us/search", args))
        assertFalse(key!!.contains("Música"))
        assertNull(AppleMusicWebCacheContract.key(search, "br", "pt-BR", path, args + ("access_token" to "private")))
        assertNull(AppleMusicWebCacheContract.key(search, "br", "pt-BR", "/us/404", args))
    }

    @Test fun truncatedAlbumIsPartialNotComplete() = runBlocking {
        val transport = AppleMusicWebTransport { _, path, params ->
            if (path.endsWith("/relationships/tracks")) {
                """{"data":[],"next":"/v1/catalog/br/albums/10/relationships/tracks?offset=3"}"""
            } else {
                """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record"},
                "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}}],
                "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
            }
        }
        val result = AppleMusicWebCatalog(transport).albumOutcome("10", "br")
        assertTrue(result is AppleMusicWebOutcome.Partial)
        val partial = result as AppleMusicWebOutcome.Partial
        assertEquals(AppleMusicWebCompleteness.UPSTREAM_INTERRUPTED, partial.reason)
        assertEquals(listOf("1"), partial.value.tracks.map { it.id })
    }

    @Test fun legacyAlbumValueDoesNotSilentlyDiscardPartialStatus() = runBlocking {
        val transport = AppleMusicWebTransport { _, path, _ ->
            if (path.endsWith("/relationships/tracks")) {
                """{"data":[],"next":"/v1/catalog/br/albums/10/relationships/tracks?offset=4"}"""
            } else {
                """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record"},
                  "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}}],
                  "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
            }
        }
        try {
            AppleMusicWebCatalog(transport).album("10", "br")
            fail("Callers of the non-typed API must not mistake an incomplete album for complete")
        } catch (_: com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebProtocolException) {
            // albumOutcome is the opt-in API for the partial tracks.
        }
    }

    @Test fun blockedAlbumPageDoesNotConvertToSilentPartial() = runBlocking {
        val transport = AppleMusicWebTransport { _, path, _ ->
            if (path.endsWith("/relationships/tracks")) throw AppleMusicWebHttpException(403)
            """{"data":[{"id":"10","type":"albums","attributes":{"name":"Record"},
                "relationships":{"tracks":{"data":[{"id":"1","type":"songs","attributes":{"name":"One"}}],
                "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
        }
        try {
            AppleMusicWebCatalog(transport).albumOutcome("10", "br")
            fail("403 must propagate, not become a usable partial")
        } catch (exc: AppleMusicWebHttpException) {
            assertEquals(403, exc.status)
        }
    }

    @Test fun failedBatchItemDoesNotCancelSuccessfulSiblingsOrReorderResults() = runBlocking {
        val results = AppleMusicWebBatch(maxConcurrency = 2).fetch(
            listOf("one", "missing", "three")
        ) { key ->
            if (key == "missing") throw AppleMusicWebHttpException(404)
            "value:$key"
        }
        assertEquals(listOf("one", "missing", "three"), results.map { it.input })
        assertTrue(results[0] is AppleMusicWebBatchResult.Success)
        assertTrue(results[1] is AppleMusicWebBatchResult.Failure)
        assertEquals(404, (results[1] as AppleMusicWebBatchResult.Failure).httpStatus)
        assertEquals("value:three", (results[2] as AppleMusicWebBatchResult.Success).value)
    }

    @Test fun batchConcurrencyIsBoundedAndCancellationIsNeverSwallowed() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val barrier = CompletableDeferred<Unit>()
        val job = async {
            AppleMusicWebBatch(maxConcurrency = 2).fetch((1..8).map(Int::toString)) { input ->
                val running = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, running) }
                try {
                    barrier.await()
                    input
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        withTimeout(2_000) {
            while (peak.get() < 2) kotlinx.coroutines.yield()
        }
        assertEquals(2, peak.get())
        job.cancel()
        barrier.complete(Unit)
        try {
            job.await()
            fail("Cancellation must propagate to the caller")
        } catch (_: CancellationException) {
            assertTrue(active.get() <= 2)
        }
    }

    @Test fun batchUnexpectedProgrammingErrorsPropagate() = runBlocking {
        try {
            AppleMusicWebBatch().fetch(listOf("first")) { throw IllegalStateException("bug") }
            fail("Programming error must not be flattened into an empty result")
        } catch (exc: IllegalStateException) {
            assertEquals("bug", exc.message)
        }
    }
}
