package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityParseResult
import com.music.bitchord.data.catalog.metadata.parseListenBrainzRecordingPopularity
import com.music.bitchord.data.catalog.metadata.publicListenBrainzRecordingRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzPopularityEvidenceTest {
    private val a = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val b = "22ad712e-ce73-9f0c-4e97-9f0e53144411"

    @Test fun nullCountsMeanUnknownNotZero() {
        val result = parseListenBrainzRecordingPopularity(
            """[{"recording_mbid":"$a","total_listen_count":null,"total_user_count":null},
                {"recording_mbid":"$b","total_listen_count":0,"total_user_count":0}]""",
            listOf(a, b),
        )
        assertEquals(
            ListenBrainzPopularityParseResult.Success(
                listOf(
                    ListenBrainzPopularityEvidence.Unknown(a),
                    ListenBrainzPopularityEvidence.Known(b, 0, 0),
                ),
            ),
            result,
        )
        val success = result as ListenBrainzPopularityParseResult.Success
        assertEquals(listOf(b), publicListenBrainzRecordingRanking(success.observations)
            .map { it.recordingMbid })
    }

    @Test fun reordersOnlyByExactMbidAndUniqueListenerCounts() {
        val result = parseListenBrainzRecordingPopularity(
            """[{"recording_mbid":"$b","total_listen_count":100,"total_user_count":10},
                {"recording_mbid":"$a","total_listen_count":200,"total_user_count":2}]""",
            listOf(a, b),
        ) as ListenBrainzPopularityParseResult.Success
        assertEquals(listOf(a, b), result.observations.map {
            when (it) {
                is ListenBrainzPopularityEvidence.Known -> it.recordingMbid
                is ListenBrainzPopularityEvidence.Unknown -> it.recordingMbid
            }
        })
        assertEquals(listOf(b, a), publicListenBrainzRecordingRanking(result.observations)
            .map { it.recordingMbid })
    }

    @Test fun rejectsUnexpectedDuplicatedNegativeAndPartialEvidence() {
        val responses = listOf(
            """[{"recording_mbid":"$b","total_listen_count":3,"total_user_count":2}]""",
            """[{"recording_mbid":"$a","total_listen_count":-1,"total_user_count":0}]""",
            """[{"recording_mbid":"$a","total_listen_count":2,"total_user_count":null}]""",
            """[{"recording_mbid":"$a","total_listen_count":1,"total_user_count":2}]""",
            """[{"recording_mbid":"$a","total_listen_count":1,"total_user_count":1},
                {"recording_mbid":"$a","total_listen_count":1,"total_user_count":1}]""",
            "not-json",
        )
        responses.forEach { response ->
            assertEquals(
                ListenBrainzPopularityParseResult.Invalid,
                parseListenBrainzRecordingPopularity(response, listOf(a)),
            )
        }
        assertEquals(ListenBrainzPopularityParseResult.Invalid,
            parseListenBrainzRecordingPopularity("[]", listOf("bad-id")))
        assertTrue(publicListenBrainzRecordingRanking(
            listOf(ListenBrainzPopularityEvidence.Unknown(a))).isEmpty())
    }
}
