package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.parseListenBrainzSimilarArtists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenBrainzSimilarArtistsTest {
    private val seed = "11111111-1111-4111-8111-111111111111"
    private val related = "22222222-2222-4222-8222-222222222222"
    private val other = "33333333-3333-4333-8333-333333333333"

    @Test fun radioEvidenceExcludesSeedAndDeduplicatesSimilarArtistIds() {
        val response = """{"$seed":[
          {"recording_mbid":"44444444-4444-4444-8444-444444444444",
            "similar_artist_mbid":"$seed","similar_artist_name":"Seed"},
          {"recording_mbid":"55555555-5555-4555-8555-555555555555",
            "similar_artist_mbid":"$related","similar_artist_name":"Real related"},
          {"recording_mbid":"66666666-6666-4666-8666-666666666666",
            "similar_artist_mbid":"$related","similar_artist_name":"Duplicate"}],
          "$other":[{"similar_artist_mbid":"$other","similar_artist_name":"Other verified artist"}]}"""
        val found = parseListenBrainzSimilarArtists(response, seed)
        assertEquals(listOf(related, other), found.map { it.mbid })
        assertEquals(listOf("Real related", "Other verified artist"), found.map { it.name })
    }

    @Test fun emptyAndMalformedRadioEvidenceCannotDisplayArtistTiles() {
        assertTrue(parseListenBrainzSimilarArtists("""{"payload":[]}""", seed).isEmpty())
        assertTrue(parseListenBrainzSimilarArtists("""{"payload":[
          {"similar_artist_mbid":"../../test","similar_artist_name":"Fake"},
          {"similar_artist_mbid":"$related","similar_artist_name":""},
          {"similar_artist_mbid":"$related","similar_artist_name":"Valid"}]}""",
          "invalid-seed").isEmpty())
    }
}
