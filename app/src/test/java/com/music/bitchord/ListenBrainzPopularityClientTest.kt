package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityHttpResponse
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzPopularityClientTest {
    private val a = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val b = "22ad712e-ce73-9f0c-4e97-9f0e53144411"

    private class FakeTransport(
        val replies: MutableList<ListenBrainzPopularityHttpResponse>,
    ) : ListenBrainzPopularityTransport {
        var calls = 0
        val requests = mutableListOf<List<String>>()
        override suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse {
            calls += 1
            requests += recordingMbids
            return replies.removeAt(0)
        }
    }

    @Test fun exactMbidsAreFetchedAndCachedWithoutTreatingUnknownAsZero() = runBlocking {
        var now = 100_000L
        val response = ListenBrainzPopularityHttpResponse(200,
            """[{"recording_mbid":"$a","total_listen_count":23,"total_user_count":4},
            {"recording_mbid":"$b","total_listen_count":null,"total_user_count":null}]""")
        val transport = FakeTransport(mutableListOf(response, response))
        val client = ListenBrainzPopularityClient(transport,
            ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = { now += it }),
            nowMillis = { now })
        val first = client.recordingPopularity(listOf(a, b))
            as ListenBrainzPopularityFetchResult.Success
        assertEquals(false, first.fromCache)
        assertEquals(listOf(
            ListenBrainzPopularityEvidence.Known(a, 23, 4),
            ListenBrainzPopularityEvidence.Unknown(b)), first.observations)
        assertEquals(true, (client.recordingPopularity(listOf(a, b))
            as ListenBrainzPopularityFetchResult.Success).fromCache)
        assertEquals(1, transport.calls)
        now += 6 * 60 * 60 * 1_000L + 1
        assertEquals(false, (client.recordingPopularity(listOf(a, b))
            as ListenBrainzPopularityFetchResult.Success).fromCache)
        assertEquals(2, transport.calls)
    }

    @Test fun retry429OnceWithBoundedWaitThenSucceed() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val transport = FakeTransport(mutableListOf(
            ListenBrainzPopularityHttpResponse(429, "", retryAfterSeconds = 1_000),
            ListenBrainzPopularityHttpResponse(200,
                """[{"recording_mbid":"$a","total_listen_count":10,"total_user_count":3}]"""),
        ))
        val client = ListenBrainzPopularityClient(transport,
            ListenBrainzRateLimiter(nowMillis = { clock }, waitMillis = { clock += it }),
            nowMillis = { clock },
            retryDelay = { waits += it; clock += it },
        )
        assertTrue(client.recordingPopularity(listOf(a)) is ListenBrainzPopularityFetchResult.Success)
        assertEquals(listOf(30_000L), waits)
        assertEquals(2, transport.calls)
    }

    @Test fun separateCallersShareOneSecondRequestSpacing() = runBlocking {
        var clock = 0L
        val starts = mutableListOf<Long>()
        val limiter = ListenBrainzRateLimiter(
            nowMillis = { clock },
            waitMillis = { clock += it },
        )
        limiter.run { starts += clock }
        limiter.run { starts += clock }
        limiter.run { starts += clock }
        assertEquals(listOf(0L, 1_000L, 2_000L), starts)
    }

    @Test fun serverRateLimitResetDefersFollowingPublicAndScrobbleRequests() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val responseA = ListenBrainzPopularityHttpResponse(
            200, """[{"recording_mbid":"$a","total_listen_count":11,"total_user_count":2}]""",
            rateLimitRemaining = 0, rateLimitResetInSeconds = 4,
        )
        val responseB = ListenBrainzPopularityHttpResponse(
            200, """[{"recording_mbid":"$b","total_listen_count":22,"total_user_count":3}]""",
        )
        val transport = FakeTransport(mutableListOf(responseA, responseB))
        val limiter = ListenBrainzRateLimiter(
            nowMillis = { clock },
            waitMillis = { millis -> waits += millis; clock += millis },
        )
        val client = ListenBrainzPopularityClient(transport, limiter, nowMillis = { clock })
        assertTrue(client.recordingPopularity(listOf(a)) is ListenBrainzPopularityFetchResult.Success)
        assertTrue(client.recordingPopularity(listOf(b)) is ListenBrainzPopularityFetchResult.Success)
        assertEquals(listOf(4_000L), waits)
        assertEquals(2, transport.calls)
        assertEquals(4_000L, clock)
    }

    @Test fun badResponseAndInvalidIdsFailClosedAndNeverCache() = runBlocking {
        val transport = FakeTransport(mutableListOf(
            ListenBrainzPopularityHttpResponse(200,
                """[{"recording_mbid":"$b","total_listen_count":2,"total_user_count":1}]"""),
            ListenBrainzPopularityHttpResponse(503, ""),
            ListenBrainzPopularityHttpResponse(503, ""),
        ))
        val limiter = ListenBrainzRateLimiter(nowMillis = { 0L }, waitMillis = {})
        val client = ListenBrainzPopularityClient(transport, limiter,
            nowMillis = { 100L }, retryDelay = {})
        assertEquals(ListenBrainzPopularityFetchResult.InvalidRequest,
            client.recordingPopularity(listOf("name-only")))
        assertEquals(0, transport.calls)
        assertEquals(ListenBrainzPopularityFetchResult.Malformed, client.recordingPopularity(listOf(a)))
        assertEquals(ListenBrainzPopularityFetchResult.Unavailable, client.recordingPopularity(listOf(a)))
        assertEquals(3, transport.calls)
    }
}
