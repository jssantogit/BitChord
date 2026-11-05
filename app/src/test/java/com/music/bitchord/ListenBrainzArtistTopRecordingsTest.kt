package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopParseResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopRecording
import com.music.bitchord.data.catalog.metadata.parseListenBrainzArtistTopRecordings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzArtistTopRecordingsTest {
    private val artist = "b7ffd2af-418f-4be2-bdd1-22f8b48613da"
    private val other = "06c3a85a-01ec-4bd9-b3ad-55c31a3d0660"
    private val recording = "13dd61c7-ce73-4e97-9f0c-9f0e53144411"
    private val another = "22ad712e-ce73-9f0c-4e97-9f0e53144411"

    @Test fun onlyArtistScopedRecordingMbidsBecomeCommunityRankingEvidence() {
        val response = """
        [
          {"recording_mbid":"$another","artist_mbids":["$other"],"recording_name":"같은 이름",
            "total_listen_count":99999,"total_user_count":999},
          {"recording_mbid":"$recording","artist_mbids":["$artist"],"recording_name":"同じタイトル",
            "total_listen_count":90,"total_user_count":7},
          {"recording_mbid":"$another","artist_mbids":["$artist","$other"],
            "total_listen_count":23,"total_user_count":4}
        ]"""
        val parsed = parseListenBrainzArtistTopRecordings(response, artist)
            as ListenBrainzArtistTopParseResult.Success
        assertEquals(listOf(recording, another), parsed.recordings.map { it.recordingMbid })
        assertEquals(listOf(90L, 23L), parsed.recordings.map { it.listens })
        assertEquals(listOf(7L, 4L), parsed.recordings.map { it.listeners })
        // Provider-supplied names cannot establish identity or a playable asset.
        assertEquals(ListenBrainzArtistTopRecording(recording, 90, 7), parsed.recordings.first())
    }

    @Test fun noUnknownCountsOrInvalidRecordingIdentityIsPromoted() {
        val response = """
        [
          {"recording_mbid":"$recording","artist_mbids":["$artist"],
            "total_listen_count":null,"total_user_count":null},
          {"recording_mbid":"not-a-mbid","artist_mbids":["$artist"],
            "total_listen_count":50,"total_user_count":5},
          {"recording_mbid":"$another","artist_mbids":["$artist"],
            "total_listen_count":0,"total_user_count":0}
        ]"""
        val parsed = parseListenBrainzArtistTopRecordings(response, artist)
            as ListenBrainzArtistTopParseResult.Success
        assertTrue(parsed.recordings.isEmpty())
    }

    @Test fun rejectsMalformedInvalidArtistAndDuplicatedConflictingRows() {
        assertEquals(ListenBrainzArtistTopParseResult.Invalid,
            parseListenBrainzArtistTopRecordings("[]", "unknown-artist"))
        assertEquals(ListenBrainzArtistTopParseResult.Invalid,
            parseListenBrainzArtistTopRecordings("not json", artist))
        val invalidCounts = """[{"recording_mbid":"$recording","artist_mbids":["$artist"],
            "total_listen_count":1,"total_user_count":2}]"""
        assertEquals(ListenBrainzArtistTopParseResult.Invalid,
            parseListenBrainzArtistTopRecordings(invalidCounts, artist))
    }
}
