package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.KtorMusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MusicBrainzHttpRecoveryTest {
    @Test fun retriesAreBoundedAndExcessiveRetryAfterDoesNotTriggerEarlyRequests() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
            val waits = mutableListOf<Long>()
            val client = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(baseUrl = server.url("/ws/2").toString()),
                retryDelayMillis = { waits.add(it) },
            )
            assertTrue(runCatching { client.get("artist") }.isFailure)
            assertEquals(3, server.requestCount)
            assertEquals(listOf(1000L, 2000L), waits)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "600"))
            val client = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(baseUrl = server.url("/ws/2").toString()),
                retryDelayMillis = { fail("Long Retry-After must surface unavailable, not retry early") },
            )
            assertTrue(runCatching { client.get("artist") }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun temporaryFailuresBackOffAndRetryWithIdentifiedRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val delays = mutableListOf<Long>()
            var now = 0L
            val client = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(baseUrl = server.url("/ws/2").toString()),
                rateLimiter = MusicBrainzRateLimiter(nowMillis = { now }, delayMillis = { now += it }),
                retryDelayMillis = { delays.add(it); now += it },
            )
            assertEquals("{\"ok\":true}", client.get("artist", mapOf("query" to "aespa")))
            assertEquals(listOf(1000L, 2000L), delays)
            assertEquals(3, server.requestCount)
            repeat(3) {
                val request = server.takeRequest()
                assertTrue(request.getHeader("User-Agent")!!.startsWith("Bitchord/"))
                assertEquals("/ws/2/artist?query=aespa", request.path)
            }
        }
    }

    @Test fun missingEntityDoesNotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val client = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(baseUrl = server.url("/ws/2").toString()),
                retryDelayMillis = { fail("404 must not retry") },
            )
            assertTrue(runCatching { client.get("recording/missing") }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationDuringBackoffStopsFurtherRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val client = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(baseUrl = server.url("/ws/2").toString()),
                retryDelayMillis = { throw CancellationException("left search") },
            )
            val failure = runCatching { client.get("artist") }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }
}
