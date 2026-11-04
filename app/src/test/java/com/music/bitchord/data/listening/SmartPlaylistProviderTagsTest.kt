package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Red/green contract: server tag support differs from an untagged library. */
class SmartPlaylistProviderTagsTest {
    private val engine = SmartPlaylistV2RuleEngine(nowEpochMs = { 1_000_000L })
    private fun song(id: String) = RuleBasedRecordingCandidate(
        recordingId = id,
        explicitRating = null,
        audibleCompletions = 0,
        lastAudibleAtEpochMs = null,
        durationMs = 100_000L,
        available = true,
    )
    private fun query(field: SmartPlaylistRuleField, op: SmartPlaylistRuleOperator,
                      text: String? = null, negate: Boolean = false): SmartPlaylistV2Query {
        val predicate = SmartPlaylistRule.Comparison(field, op, textValue = text)
        return SmartPlaylistV2Query(
            rules = if (negate) SmartPlaylistRule.Not(predicate) else predicate,
            sort = SmartPlaylistV2Sort.RECORDING_ID_ASC,
        )
    }
    private fun ids(rows: List<RuleBasedRecordingCandidate>, q: SmartPlaylistV2Query) =
        engine.preview(rows, q).map { it.recordingId }

    @Test fun genreMatchesMultiValuesButDoesNotInventUnknownOrNegativeEvidence() {
        val tracks = listOf(
            song("filled").copy(genres = listOf("Dream Pop", "K-Pop"), genreEvidenceUnknown = false),
            song("empty").copy(genres = emptyList(), genreEvidenceUnknown = false),
            song("unsupported"),
            song("partial").copy(genres = listOf("Dream Pop"), genreEvidenceUnknown = true),
        )
        val field = SmartPlaylistRuleField.GENRE
        assertEquals(listOf("filled", "partial"),
            ids(tracks, query(field, SmartPlaylistRuleOperator.CONTAINS, "dream")))
        assertEquals(listOf("empty"),
            ids(tracks, query(field, SmartPlaylistRuleOperator.IS_MISSING)))
        assertEquals(listOf("empty"),
            ids(tracks, query(field, SmartPlaylistRuleOperator.IS_PRESENT, negate = true)))
        assertEquals(listOf("filled"),
            ids(tracks, query(field, SmartPlaylistRuleOperator.NOT_EQUAL, "rock")))
    }

    @Test fun moodAllowsExplicitEmptyTagsWithoutTurningUnsupportedIntoMissing() {
        val tracks = listOf(
            song("filled").copy(moods = listOf("Calm", "Bright"), moodEvidenceUnknown = false),
            song("empty").copy(moods = emptyList(), moodEvidenceUnknown = false),
            song("unsupported"),
        )
        assertEquals(listOf("filled"),
            ids(tracks, query(SmartPlaylistRuleField.MOOD,
                SmartPlaylistRuleOperator.EQUAL, "calm")))
        assertEquals(listOf("empty"),
            ids(tracks, query(SmartPlaylistRuleField.MOOD,
                SmartPlaylistRuleOperator.IS_MISSING)))
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(SmartPlaylistRule.Comparison(
                SmartPlaylistRuleField.MOOD,
                SmartPlaylistRuleOperator.GREATER_THAN,
                value = 2.0,
            ))
        }
    }
}
