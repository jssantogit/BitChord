package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityHttpResponse
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityTransport
import com.music.bitchord.data.catalog.metadata.ListenBrainzRateLimiter
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzSitewideClientTest {
    private val recording = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"

    private class Fake : ListenBrainzPopularityTransport {
        val replies = mutableListOf<ListenBrainzPopularityHttpResponse>()
        val calls = mutableListOf<Pair<String, Int>>()

        override suspend fun recordingPopularity(recordingMbids: List<String>) =
            ListenBrainzPopularityHttpResponse(503, "")

        override suspend fun topRecordingsForArtist(artistMbid: String) =
            ListenBrainzPopularityHttpResponse(503, "")

        override suspend fun sitewideRecordings(
            range: ListenBrainzSitewideRange, count: Int,
        ): ListenBrainzPopularityHttpResponse {
            calls += range.apiValue to count
            return replies.removeAt(0)
        }
    }

    @Test fun weeklyStatsReuseCacheAndNeverConfuseMonthlyData() = runBlocking {
        var now = 1_000L
        val fake = Fake().apply {
            replies += ListenBrainzPopularityHttpResponse(200,
                """{"payload":{"range":"week","offset":0,"recordings":[
                {"recording_mbid":"$recording","listen_count":14}]}}""")
            replies += ListenBrainzPopularityHttpResponse(204, "")
        }
        val client = ListenBrainzPopularityClient(
            fake, ListenBrainzRateLimiter(nowMillis = { now }, waitMillis = { now += it }),
            nowMillis = { now },
        )
        val fresh = client.sitewideRecordings(ListenBrainzSitewideRange.WEEK)
            as ListenBrainzSitewideFetchResult.Success
        assertEquals(listOf(recording), fresh.recordings.map { it.recordingMbid })
        assertEquals(false, fresh.fromCache)
        val cached = client.sitewideRecordings(ListenBrainzSitewideRange.WEEK)
            as ListenBrainzSitewideFetchResult.Success
        assertEquals(true, cached.fromCache)
        val month = client.sitewideRecordings(ListenBrainzSitewideRange.MONTH)
            as ListenBrainzSitewideFetchResult.Success
        assertTrue(month.recordings.isEmpty())
        assertEquals(listOf("week" to 25, "month" to 25), fake.calls)
    }

    @Test fun sitewideRequestMustBeBoundedAndFailClosedOnWrongRange() = runBlocking {
        val fake = Fake().apply {
            replies += ListenBrainzPopularityHttpResponse(200,
                """{"payload":{"range":"year","recordings":[
                {"recording_mbid":"$recording","listen_count":100}]}}""")
        }
        val client = ListenBrainzPopularityClient(
            fake, ListenBrainzRateLimiter(nowMillis = { 0L }, waitMillis = {}),
            nowMillis = { 100L }, retryDelay = {},
        )
        assertEquals(ListenBrainzSitewideFetchResult.InvalidRequest,
            client.sitewideRecordings(ListenBrainzSitewideRange.WEEK, count = 0))
        assertEquals(ListenBrainzSitewideFetchResult.InvalidRequest,
            client.sitewideRecordings(ListenBrainzSitewideRange.WEEK, count = 101))
        assertEquals(ListenBrainzSitewideFetchResult.Malformed,
            client.sitewideRecordings(ListenBrainzSitewideRange.WEEK))
        assertEquals(1, fake.calls.size)
    }
}
