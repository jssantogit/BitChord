package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesCatalogRateLimiter
import com.music.bitchord.data.catalog.metadata.KtorItunesCatalogTransport
import com.music.bitchord.data.providers.BuiltInProviderDescriptors
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderRequestDeniedException
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogResult
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesCatalogRateLimiterTest {

    @Test fun anEditorialSearchUsesOnlyEditorialPermission() = runBlocking {
        val key = BuiltInProviderKeys.ITUNES
        fun policy(search: Boolean, editorial: Boolean) = ProviderCapabilityPolicy(
            listOf(BuiltInProviderDescriptors.itunes(ProviderReadiness.READY)),
            mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(key, search, 0)),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to
                    listOf(ProviderChoice(key, editorial, 0)),
            ),
        )
        val gate = ProviderPolicyGate(policy(search = false, editorial = true))
        var contacts = 0
        val transport = KtorItunesCatalogTransport(
            limiter = ItunesCatalogRateLimiter(clockMs = { 1000L }, sleep = {}),
            admission = RateLimitedProviderTransport { gate },
            testExchange = { _, _ -> contacts++; """{"resultCount":0,"results":[]}""" },
        )
        assertTrue(transport.get("search", mapOf("term" to "jazz"),
            ProviderCapability.EDITORIAL_DISCOGRAPHY).contains("resultCount"))
        try {
            transport.get("search", mapOf("term" to "jazz"))
            throw AssertionError("General Search must still be denied")
        } catch (_: ProviderRequestDeniedException) { }
        assertEquals(1, contacts)
        gate.replace(policy(search = true, editorial = false))
        try {
            transport.get("search", mapOf("term" to "jazz"),
                ProviderCapability.EDITORIAL_DISCOGRAPHY)
            throw AssertionError("Editorial Search must be denied")
        } catch (_: ProviderRequestDeniedException) { }
        assertTrue(transport.get("search", mapOf("term" to "jazz")).contains("resultCount"))
        assertEquals(2, contacts)
    }

    @Test fun realItunesTransportSeparatesSearchAndDiscographyAdmission() = runBlocking {
        val itunes = BuiltInProviderKeys.ITUNES
        val gate = ProviderPolicyGate(ProviderCapabilityPolicy(
            descriptors = listOf(BuiltInProviderDescriptors.itunes(ProviderReadiness.READY)),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(itunes, false, 0)),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(ProviderChoice(itunes, true, 0)),
            ),
        ))
        var contacts = 0
        val transport = KtorItunesCatalogTransport(
            limiter = ItunesCatalogRateLimiter(clockMs = { 1000L }, sleep = { }),
            admission = RateLimitedProviderTransport { gate },
            testExchange = { _, _ -> contacts++; """{"resultCount":0,"results":[]}""" },
        )
        try {
            transport.get("search", mapOf("term" to "not-authorized"))
            throw AssertionError("search must be denied before HTTP")
        } catch (_: ProviderRequestDeniedException) { }
        assertEquals(0, contacts)
        assertTrue(transport.get("lookup", mapOf("id" to "100")).contains("resultCount"))
        assertEquals(1, contacts)
        gate.replace(ProviderCapabilityPolicy(
            descriptors = listOf(BuiltInProviderDescriptors.itunes(ProviderReadiness.READY)),
            choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(itunes, false, 0)),
                ProviderCapability.EDITORIAL_DISCOGRAPHY to listOf(ProviderChoice(itunes, false, 0)),
            ),
        ))
        try {
            transport.get("lookup", mapOf("id" to "100"))
            throw AssertionError("lookup must respect independent opt-out")
        } catch (_: ProviderRequestDeniedException) { }
        assertEquals(1, contacts)
    }

    @Test fun sharedRateBudgetSpacesDispatchStartsWithoutRealSleeping() = runBlocking {
        var now = 1_000L
        val starts = mutableListOf<Long>()
        val sleeps = mutableListOf<Long>()
        val limiter = ItunesCatalogRateLimiter(
            clockMs = { now },
            sleep = { ms -> sleeps += ms; now += ms },
        )
        limiter.run { starts += now }
        now += 100L
        limiter.run { starts += now }
        assertEquals(listOf(1_000L, 4_100L), starts)
        assertEquals(listOf(3_000L), sleeps)
    }

    @Test fun failureDoesNotLeaveRequestBudgetLocked() = runBlocking {
        var now = 10_000L
        val limiter = ItunesCatalogRateLimiter(
            clockMs = { now },
            sleep = { now += it },
        )
        try {
            limiter.run<Unit> { throw IllegalStateException("network failed") }
        } catch (_: IllegalStateException) {
            // The lock must be released despite the transport error.
        }
        val result = limiter.run { "next request" }
        assertEquals("next request", result)
        assertEquals(13_100L, now)
    }

    @Test fun transportCancellationRemainsCancellationNotAnEmptyCatalog() = runBlocking {
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(
                path: String, parameters: Map<String, String>,
            ): String = throw CancellationException("search replaced")
        }
        try {
            ItunesCatalogClient(transport).lookupAlbum(10)
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    @Test fun artistLookupRejectsForeignArtistsAndDoesNotInferReleaseTypes() = runBlocking {
        val transport = object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String, String>): String =
                """{"resultCount":3,"results":[
                    {"wrapperType":"artist","artistId":500,"artistName":"NewJeans"},
                    {"wrapperType":"collection","collectionId":601,"artistId":500,"collectionName":"Get Up","artistName":"NewJeans"},
                    {"wrapperType":"collection","collectionId":602,"artistId":501,"collectionName":"Get Up","artistName":"Other"}
                ]}"""
        }
        val result = ItunesCatalogClient(transport).lookupArtistAlbums(500, country = "US")
        val items = (result as ItunesCatalogResult.Success).items
        assertEquals(listOf(500L, 601L), items.map { it.id })
        assertEquals("itunes:us:album:601", items.last().scopedId)
    }
}
