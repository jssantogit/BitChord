package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.KtorMusicBrainzTransport
import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzXmlTransportLimitTest {
    @Test fun boundedXmlRequestUsesRealHttpBodyAndPreservesRequestMetadata() = runBlocking {
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            server.enqueue(MockResponse().setBody("<metadata/>"))
            val client = client(server, http)

            assertEquals("<metadata/>", client.getLimited(
                "release/541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
                mapOf("fmt" to "xml", "inc" to "recordings"),
                maxResponseBytes = 64,
            ))

            val request = server.takeRequest()
            assertTrue(request.path!!.startsWith("/ws/2/release/541adf5a-48d4-4f7b-a7bb-afd3cea5af27?"))
            assertTrue(request.path!!.contains("fmt=xml"))
            assertTrue(request.path!!.contains("inc=recordings"))
            assertEquals("test-agent", request.getHeader("User-Agent"))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test fun declaredOversizeContentLengthIsRejected() = runBlocking {
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            server.enqueue(MockResponse().setBody("x".repeat(4096)))
            val failure = runCatching {
                client(server, http).getLimited("release/test", mapOf("fmt" to "xml"), maxResponseBytes = 64)
            }
            assertTrue(failure.isFailure)
            assertEquals(1, server.requestCount)
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test fun chunkedBodyWithoutContentLengthIsStoppedByStreamingLimit() = runBlocking {
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            server.enqueue(MockResponse().setChunkedBody("x".repeat(100_000), 257))
            val failure = runCatching {
                client(server, http).getLimited("release/test", mapOf("fmt" to "xml"), maxResponseBytes = 64)
            }
            assertTrue(failure.isFailure)
            assertEquals(1, server.requestCount)
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test fun chunkedBodyWithFalseSmallContentLengthIsStillStreamLimited() = runBlocking {
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            server.enqueue(MockResponse().setChunkedBody("x".repeat(100_000), 257)
                .setHeader("Content-Length", "1"))
            val failure = runCatching {
                client(server, http).getLimited("release/test", mapOf("fmt" to "xml"), maxResponseBytes = 64)
            }
            assertTrue(failure.isFailure)
            assertEquals(1, server.requestCount)
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test fun jsonRequestsKeepTheExistingUnboundedTextContract() = runBlocking {
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            val json = "{" + "\"data\":\"${"x".repeat(256)}\"}"
            server.enqueue(MockResponse().setBody(json))
            assertEquals(json, client(server, http).get("release/test", mapOf("fmt" to "json")))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test fun boundedXmlRequestsRetainClientRetryAndLimiterBehavior() = runBlocking {
        val server = MockWebServer()
        val http = HttpClient(OkHttp) { expectSuccess = true }
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("<metadata/>"))
            var now = 0L
            val client = MusicBrainzClient(
                transport = KtorMusicBrainzTransport(server.url("/ws/2").toString(), http),
                rateLimiter = MusicBrainzRateLimiter(nowMillis = { now }, delayMillis = { now += it }),
                userAgent = "test-agent",
                retryDelayMillis = {},
            )

            assertEquals("<metadata/>", client.getLimited("release/test", mapOf("fmt" to "xml"), 64))
            assertEquals(2, server.requestCount)
            assertEquals(1_000L, now)
            assertEquals("test-agent", server.takeRequest().getHeader("User-Agent"))
            assertEquals("test-agent", server.takeRequest().getHeader("User-Agent"))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    private fun client(server: MockWebServer, http: HttpClient) = MusicBrainzClient(
        transport = KtorMusicBrainzTransport(server.url("/ws/2").toString(), http),
        rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
        userAgent = "test-agent",
    )
}
