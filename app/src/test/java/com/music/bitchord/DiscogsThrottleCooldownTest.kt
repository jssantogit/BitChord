package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsThrottleCooldownTest {
    @Test fun throttledRequestDefersSubsequentCallForRetryAfterInsteadOfStormingApi() = runBlocking {
        var monotonic = 0L
        val requestsAt = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { monotonic },
            delayMillis = { monotonic += it },
            minimumIntervalMs = 0L,
        )
        var count = 0
        val client = DiscogsClient(
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    requestsAt += monotonic
                    return if (++count == 1) DiscogsHttpResponse(
                        status = 429, body = "", retryAfterSeconds = 12L,
                    ) else DiscogsHttpResponse(
                        status = 200, body = """{"id":5,"title":"Album"}""",
                    )
                }
            },
            enabled = true,
            limiter = limiter,
        )
        assertEquals(DiscogsLookupResult.Throttled(12L), client.fetchRelease(5L))
        assertTrue(client.fetchRelease(5L) is DiscogsLookupResult.Found)
        assertEquals(2, requestsAt.size)
        assertTrue("429 cooldown must be applied to the NEXT call", requestsAt[1] >= 12_000L)
    }

    @Test fun throttledRequestWithNoRetryHeaderAlsoGetsABoundedCooldown() = runBlocking {
        var monotonic = 0L
        val requestsAt = mutableListOf<Long>()
        val limiter = DiscogsRateLimiter(
            nowMillis = { monotonic },
            delayMillis = { monotonic += it },
            minimumIntervalMs = 0L,
        )
        var count = 0
        val client = DiscogsClient(
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    requestsAt += monotonic
                    return if (++count == 1) DiscogsHttpResponse(429, "")
                    else DiscogsHttpResponse(404, "")
                }
            },
            enabled = true, limiter = limiter,
        )
        assertEquals(DiscogsLookupResult.Throttled(null), client.fetchRelease(5L))
        assertEquals(DiscogsLookupResult.Missing, client.fetchRelease(5L))
        assertTrue("Missing Retry-After must not trigger an immediate flood",
            requestsAt[1] >= 3_000L)
        assertTrue("Cooldown must be bounded", requestsAt[1] <= 60_000L)
    }
}
