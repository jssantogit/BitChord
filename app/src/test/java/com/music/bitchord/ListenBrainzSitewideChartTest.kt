package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideParseResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.data.catalog.metadata.parseListenBrainzSitewideRecordings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzSitewideChartTest {
    private val first = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val second = "22ad712e-ce73-9f0c-4e97-9f0e53144411"

    @Test fun sitewideWeekAcceptsOnlyExactRecordedMbidsAndPositiveCounts() {
        val raw = """{
          "payload": {
            "range": "week", "offset": 0, "last_updated": 1793790000,
            "recordings": [
              {"recording_mbid": "$first", "track_name":"First", "listen_count": 120},
              {"recording_mbid": "", "listen_count": 9900},
              {"artist_name": "A name is not a recording identifier", "listen_count": 800},
              {"recording_mbid": "$second", "listen_count": 60},
              {"recording_mbid": "11111111-1111-1111-1111-111111111111", "listen_count": 0}
            ]
          }
        }"""
        assertEquals(
            ListenBrainzSitewideParseResult.Success(
                listOf(
                    ListenBrainzSitewideRecording(first, 120L),
                    ListenBrainzSitewideRecording(second, 60L),
                ),
                "week", 1793790000L,
            ),
            parseListenBrainzSitewideRecordings(raw, ListenBrainzSitewideRange.WEEK),
        )
    }

    @Test fun sitewideResultsCannotChangeWindowOrAttachUnknownCounts() {
        val wrongWindow = """{"payload":{"range":"year","recordings":[
            {"recording_mbid":"$first","listen_count":9000}]}}"""
        assertEquals(ListenBrainzSitewideParseResult.Invalid,
            parseListenBrainzSitewideRecordings(wrongWindow, ListenBrainzSitewideRange.WEEK))
        val negative = """{"payload":{"range":"week","recordings":[
            {"recording_mbid":"$first","listen_count":-2}]}}"""
        assertEquals(ListenBrainzSitewideParseResult.Invalid,
            parseListenBrainzSitewideRecordings(negative, ListenBrainzSitewideRange.WEEK))
        val duplicate = """{"payload":{"range":"week","recordings":[
            {"recording_mbid":"$first","listen_count":4},
            {"recording_mbid":"$first","listen_count":5}]}}"""
        assertEquals(ListenBrainzSitewideParseResult.Invalid,
            parseListenBrainzSitewideRecordings(duplicate, ListenBrainzSitewideRange.WEEK))
        assertEquals(ListenBrainzSitewideParseResult.Invalid,
            parseListenBrainzSitewideRecordings("{}", ListenBrainzSitewideRange.MONTH))
    }

    @Test fun missingMbidsDoNotBecomeInventedRecordings() {
        val response = """{"payload":{"range":"month","recordings":[
            {"track_name":"Only a title","listen_count":52}]}}"""
        val result = parseListenBrainzSitewideRecordings(response, ListenBrainzSitewideRange.MONTH)
        assertTrue(result is ListenBrainzSitewideParseResult.Success)
        assertTrue((result as ListenBrainzSitewideParseResult.Success).recordings.isEmpty())
    }
}
