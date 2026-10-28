package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A contextual Songs filter must never require invented Rating/Listening evidence. */
class ContextualSmartFilterTest {
    private fun track(id: String, title: String = "Song $id", durationMs: Long? = 180_000) =
        CanonicalBrowseTrack(id, title, "NewJeans", durationMs, null,
            listOf(CanonicalBrowseMediaAsset("asset:$id", "opensubsonic:current")))

    private fun filter(rule: SmartPlaylistRule) =
        SavedSmartPlaylistV2Filter("f:one", "Example", rule)

    @Test fun onlyCanonicalRecordingFieldsAreAllowedForBrowseWithoutExtraQueries() {
        val text = SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
            SmartPlaylistRuleOperator.CONTAINS, textValue = "get up")
        val duration = SmartPlaylistRule.Comparison(SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleOperator.GREATER_OR_EQUAL, value = 100_000.0)
        val nested = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL,
            listOf(text, SmartPlaylistRule.Not(duration)))
        assertTrue(filter(nested).isSupportedForCanonicalSongBrowse())
        assertFalse(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleOperator.IS_MISSING,
        )).isSupportedForCanonicalSongBrowse())
        assertFalse(filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.RELEASE_TITLE,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )).isSupportedForCanonicalSongBrowse())
        assertFalse(filter(SmartPlaylistRule.Reference("another-playlist"))
            .isSupportedForCanonicalSongBrowse())
    }

    @Test fun filtersProviderScopedSongsWithoutReorderingOrFetchingFromOtherAccounts() {
        val rows = listOf(track("first", "Get Up"), track("second", "Ditto"),
            track("third", "Get Up (Live)", 20_000))
        val rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.TITLE,
                SmartPlaylistRuleOperator.CONTAINS, textValue = "GET UP"),
            SmartPlaylistRule.Comparison(SmartPlaylistRuleField.DURATION_MS,
                SmartPlaylistRuleOperator.GREATER_THAN, value = 100_000.0),
        ))
        assertEquals(listOf("first"),
            filterCanonicalBrowseSongs(rows, filter(rule)).map { it.recordingId })
        assertEquals(listOf("first", "third"),
            filterCanonicalBrowseSongs(rows, filter(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleOperator.CONTAINS,
                textValue = "get up",
            ))).map { it.recordingId })
    }

    @Test fun noImplicitPlaylistLimitOrFallbackToUnfilteredResults() {
        val rows = (1..10_101).map { track("rec:$it") }
        val rule = SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DURATION_MS,
            SmartPlaylistRuleOperator.IS_PRESENT,
        )
        assertEquals(10_101, filterCanonicalBrowseSongs(rows, filter(rule)).size)
        val forbidden = filter(SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.EXPLICIT_RATING,
            SmartPlaylistRuleOperator.IS_MISSING,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            filterCanonicalBrowseSongs(rows, forbidden)
        }
        val unknownDuration = listOf(track("missing", durationMs = null))
        assertEquals(0, filterCanonicalBrowseSongs(unknownDuration,
            filter(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.DURATION_MS,
                SmartPlaylistRuleOperator.GREATER_THAN, value = 20_000.0,
            ))).size)
    }
}
