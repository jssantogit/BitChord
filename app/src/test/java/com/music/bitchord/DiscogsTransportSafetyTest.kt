package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsHttpResponse
import com.music.bitchord.data.catalog.metadata.discogs.KtorDiscogsTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiscogsTransportSafetyTest {
    @Test fun readOnlyTransportSendsIdentifiedRequestWithoutAuthenticationOrMarketplaceCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":17,"title":"Album"}"""))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            val response = transport.get("releases/17", "BitChord/Test")
            assertEquals(200, response.status)
            val request = server.takeRequest()
            assertEquals("/releases/17", request.path)
            assertEquals("BitChord/Test", request.getHeader("User-Agent"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(request.getHeader("Accept")!!.contains("discogs"))
        }
    }

    @Test fun rejectsArbitraryUserURLsAndDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", "/users/private"))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            assertEquals(302, transport.get("releases/17", "BitChord/Test").status)
            assertEquals(1, server.requestCount)
            assertTrue(runCatching { transport.get("../users/private", "BitChord/Test") }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedBodyOrServerAnnouncedLengthIsRejectedWithoutParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Length", "999999999"))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            assertEquals(413, transport.get("releases/42", "BitChord/Test").status)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("a".repeat(140_000)))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            assertEquals(413, transport.get("releases/42", "BitChord/Test").status)
        }
    }

    @Test fun acceptsBoundedNumericRetryAfterWithoutRetryLoop() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "15"))
            val transport = KtorDiscogsTransport(baseUrl = server.url("/").toString())
            assertEquals(DiscogsHttpResponse(429, "", 15),
                transport.get("releases/42", "BitChord/Test"))
            assertEquals(1, server.requestCount)
        }
    }
}
