package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesCatalogRateLimiter
import com.music.bitchord.data.catalog.metadata.ItunesCatalogClient
import com.music.bitchord.data.catalog.metadata.ItunesCatalogResult
import com.music.bitchord.data.catalog.metadata.ItunesCatalogTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesCatalogRateLimiterTest {
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
