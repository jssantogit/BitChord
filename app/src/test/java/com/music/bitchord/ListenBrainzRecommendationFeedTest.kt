package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationParseResult
import com.music.bitchord.data.catalog.metadata.parseListenBrainzRecommendationFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzRecommendationFeedTest {
    private val a = "526bd613-fddd-4bd6-9137-ab709ac74cab"
    private val b = "a6081bc1-2a76-4984-b21f-38bc3dcca3a5"
    private val now = 1_783_000_000L

    @Test fun onlyValidScoredRecordingMbidsBecomeCandidates() {
        val result = parseListenBrainzRecommendationFeed(
            """{"payload":{"entity":"recording","user_name":"MyListener",
                "last_updated":1782999900,"count":5,"offset":0,"total_mbid_count":20,
                "mbids":[{"recording_mbid":"526BD613-FDDD-4BD6-9137-AB709AC74CAB","score":9.345},
                         {"recording_mbid":"526bd613-fddd-4bd6-9137-ab709ac74cab","score":2.0},
                         {"recording_mbid":"a6081bc1-2a76-4984-b21f-38bc3dcca3a5","score":6.998},
                         {"recording_mbid":"not-a-mbid","score":30},
                         {"recording_mbid":"a6081bc1-2a76-4984-b21f-38bc3dcca3a5","score":-1}]}}""",
            expectedUser = "mylistener", nowSeconds = now,
        )
        assertTrue(result is ListenBrainzRecommendationParseResult.Success)
        result as ListenBrainzRecommendationParseResult.Success
        assertEquals(listOf(a, b), result.items.map { it.recordingMbid })
        assertEquals(9.345, result.items[0].score, 0.001)
        assertEquals(1_782_999_900L, result.lastUpdatedSeconds)
        assertEquals(20, result.totalCount)
    }

    @Test fun mismatchedAccountAndUnverifiedEntityAreRejected() {
        val body = """{"payload":{"entity":"recording","user_name":"other",
            "last_updated":1782999900,"mbids":[{"recording_mbid":"526bd613-fddd-4bd6-9137-ab709ac74cab","score":5}]}}"""
        assertEquals(
            ListenBrainzRecommendationParseResult.Malformed,
            parseListenBrainzRecommendationFeed(body, "mylistener", now),
        )
        val notRecording = body.replace("other", "mylistener").replace("\"recording\"", "\"artist\"")
        assertEquals(
            ListenBrainzRecommendationParseResult.Malformed,
            parseListenBrainzRecommendationFeed(notRecording, "mylistener", now),
        )
    }

    @Test fun hostileJsonTypesFailClosedWithoutThrowing() {
        val badTypes = """{"payload":{"entity":[],"user_name":{},"last_updated":{},
            "mbids":[{"recording_mbid":[],"score":{}}]}}"""
        assertEquals(
            ListenBrainzRecommendationParseResult.Malformed,
            parseListenBrainzRecommendationFeed(badTypes, "mylistener", now),
        )
    }

    @Test fun invalidOrUnverifiableFreshnessCannotBePublishedAsRecommendations() {
        val data = """{"payload":{"entity":"recording","user_name":"mylistener",
            "last_updated":0,"mbids":[{"recording_mbid":"526bd613-fddd-4bd6-9137-ab709ac74cab","score":10}]}}"""
        assertEquals(
            ListenBrainzRecommendationParseResult.Malformed,
            parseListenBrainzRecommendationFeed(data, "mylistener", now),
        )
        assertEquals(
            ListenBrainzRecommendationParseResult.Malformed,
            parseListenBrainzRecommendationFeed(data.replace("\"last_updated\":0", "\"last_updated\":1783999900"), "mylistener", now),
        )
    }
}
