package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityHttpResponse
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzPopularityClientTest {
    private val a = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val b = "22ad712e-ce73-9f0c-4e97-9f0e53144411"

    private class FakeTransport(
        val replies: MutableList<ListenBrainzPopularityHttpResponse>,
        val artistReplies: MutableList<ListenBrainzPopularityHttpResponse> = mutableListOf(),
    ) : ListenBrainzPopularityTransport {
        var calls = 0
        var artistCalls = 0
        val requests = mutableListOf<List<String>>()
        override suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse {
            calls += 1
            requests += recordingMbids
            return replies.removeAt(0)
        }
        override suspend fun topRecordingsForArtist(artistMbid: String): ListenBrainzPopularityHttpResponse {
            artistCalls += 1
            return artistReplies.removeAt(0)
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

    @Test fun slowPublicHttpCannotBlockASecondListenBrainzRequest() = runBlocking {
        val requestEntered = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        var clock = 0L
        val starts = mutableListOf<Long>()
        val limiter = ListenBrainzRateLimiter(
            nowMillis = { clock },
            waitMillis = { clock += it },
        )
        val slow = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.run {
                starts += clock
                requestEntered.complete(Unit)
                releaseResponse.await()
            }
        }
        requestEntered.await()
        val fast = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.run {
                starts += clock
                "chart"
            }
        }
        // A global playlist search must not hold up Em alta for up to 90s.
        assertEquals("chart", kotlinx.coroutines.withTimeout(2_000L) { fast.await() })
        assertEquals(listOf(0L, 1_000L), starts)
        releaseResponse.complete(Unit)
        slow.await()
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

    @Test fun artistTopRankingsUseExactArtistMBIDAndCachePublicResults() = runBlocking {
        val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
        var clock = 1_000L
        val body = """[{"recording_mbid":"$a","artist_mbids":["$artist"],
            "recording_name":"同名","total_listen_count":15,"total_user_count":4}]"""
        val transport = FakeTransport(
            replies = mutableListOf(),
            artistReplies = mutableListOf(ListenBrainzPopularityHttpResponse(200, body)),
        )
        val client = ListenBrainzPopularityClient(
            transport, ListenBrainzRateLimiter(nowMillis = { clock }, waitMillis = { clock += it }),
            nowMillis = { clock },
        )
        val first = client.topRecordingsForArtist(artist) as ListenBrainzArtistTopFetchResult.Success
        assertEquals(false, first.fromCache)
        assertEquals(listOf(a), first.recordings.map { it.recordingMbid })
        assertEquals(true, (client.topRecordingsForArtist(artist)
            as ListenBrainzArtistTopFetchResult.Success).fromCache)
        assertEquals(1, transport.artistCalls)
        assertEquals(ListenBrainzArtistTopFetchResult.InvalidRequest,
            client.topRecordingsForArtist("unknown"))
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

    @Test fun reorderedExactMbidBatchReusesCacheWithoutChangingCallerOrder() = runBlocking {
        val body = """[{"recording_mbid":"$a","total_listen_count":10,"total_user_count":3},
            {"recording_mbid":"$b","total_listen_count":20,"total_user_count":4}]"""
        val transport = FakeTransport(mutableListOf(ListenBrainzPopularityHttpResponse(200, body)))
        val client = ListenBrainzPopularityClient(
            transport, ListenBrainzRateLimiter(nowMillis = { 0L }, waitMillis = {}),
            nowMillis = { 500L },
        )
        val first = client.recordingPopularity(listOf(b, a))
            as ListenBrainzPopularityFetchResult.Success
        val second = client.recordingPopularity(listOf(a, b))
            as ListenBrainzPopularityFetchResult.Success
        fun ids(result: ListenBrainzPopularityFetchResult.Success) =
            result.observations.map {
                when (it) {
                    is ListenBrainzPopularityEvidence.Known -> it.recordingMbid
                    is ListenBrainzPopularityEvidence.Unknown -> it.recordingMbid
                }
            }
        assertEquals(listOf(b, a), ids(first))
        assertEquals(listOf(a, b), ids(second))
        assertEquals(true, second.fromCache)
        assertEquals(1, transport.calls)
    }

    @Test fun concurrentIdenticalPopularityRequestsShareOneNetworkFetch() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val response = ListenBrainzPopularityHttpResponse(
            200, """[{"recording_mbid":"$a","total_listen_count":25,"total_user_count":5}]""",
        )
        val transport = object : ListenBrainzPopularityTransport {
            override suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse {
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    releaseFirst.await()
                }
                return response
            }
            override suspend fun topRecordingsForArtist(artistMbid: String) = response
        }
        val client = ListenBrainzPopularityClient(
            transport, ListenBrainzRateLimiter(nowMillis = { 0L }, waitMillis = {}),
            nowMillis = { 100L },
        )
        val one = async(start = CoroutineStart.UNDISPATCHED) {
            client.recordingPopularity(listOf(a))
        }
        entered.await()
        val two = async(start = CoroutineStart.UNDISPATCHED) {
            client.recordingPopularity(listOf(a))
        }
        releaseFirst.complete(Unit)
        val first = one.await() as ListenBrainzPopularityFetchResult.Success
        val second = two.await() as ListenBrainzPopularityFetchResult.Success
        assertEquals(false, first.fromCache)
        assertEquals(true, second.fromCache)
        assertEquals(1, calls)
    }

    @Test fun cancellationReleasesFetchLockAndAllowsNextAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        var calls = 0
        val transport = object : ListenBrainzPopularityTransport {
            override suspend fun recordingPopularity(recordingMbids: List<String>): ListenBrainzPopularityHttpResponse {
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    neverFinishes.await()
                }
                return ListenBrainzPopularityHttpResponse(200,
                    """[{"recording_mbid":"$a","total_listen_count":8,"total_user_count":2}]""")
            }
            override suspend fun topRecordingsForArtist(artistMbid: String) =
                ListenBrainzPopularityHttpResponse(503, "")
        }
        val client = ListenBrainzPopularityClient(
            transport, ListenBrainzRateLimiter(nowMillis = { 0L }, waitMillis = {}),
            nowMillis = { 50L },
        )
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            client.recordingPopularity(listOf(a))
        }
        entered.await()
        cancelled.cancel()
        cancelled.join()
        assertTrue(client.recordingPopularity(listOf(a)) is ListenBrainzPopularityFetchResult.Success)
        assertEquals(2, calls)
    }

    @Test fun concurrentArtistTopRequestsShareFetchedEvidence() = runBlocking {
        val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
        val recording = a
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val transport = object : ListenBrainzPopularityTransport {
            override suspend fun recordingPopularity(recordingMbids: List<String>) =
                ListenBrainzPopularityHttpResponse(503, "")
            override suspend fun topRecordingsForArtist(artistMbid: String): ListenBrainzPopularityHttpResponse {
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return ListenBrainzPopularityHttpResponse(200,
                    """[{"recording_mbid":"$recording","artist_mbids":["$artist"],
                    "total_listen_count":42,"total_user_count":6}]""")
            }
        }
        val client = ListenBrainzPopularityClient(
            transport, ListenBrainzRateLimiter(nowMillis = { 0L }, waitMillis = {}),
            nowMillis = { 70L },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            client.topRecordingsForArtist(artist)
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            client.topRecordingsForArtist(artist)
        }
        release.complete(Unit)
        assertEquals(false, (first.await() as ListenBrainzArtistTopFetchResult.Success).fromCache)
        assertEquals(true, (second.await() as ListenBrainzArtistTopFetchResult.Success).fromCache)
        assertEquals(1, calls)
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
