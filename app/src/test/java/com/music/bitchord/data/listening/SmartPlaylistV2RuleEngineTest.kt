package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract first: AND/OR/NOT must never promote absent evidence into a match. */
class SmartPlaylistV2RuleEngineTest {
    private val now = 90L * 86_400_000L
    private val planner = SmartPlaylistV2RuleEngine(nowEpochMs = { now })

    private fun candidate(
        id: String,
        rating: Double? = null,
        completed: Long = 0,
        lastAudible: Long? = null,
        duration: Long? = 180_000L,
        available: Boolean = true,
    ) = RuleBasedRecordingCandidate(id, rating, completed, lastAudible, duration, available)

    private fun check(
        field: SmartPlaylistRuleField,
        operator: SmartPlaylistRuleOperator,
        value: Double? = null,
    ) = SmartPlaylistRule.Comparison(field, operator, value)

    @Test fun nestedAllAnySelectsExactlyTheMatchingRecording() {
        val query = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(
                    check(SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 4.0),
                    check(SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleOperator.GREATER_THAN, 240_000.0),
                )),
            )),
        )
        val result = planner.preview(listOf(
            candidate("both", 5.0, completed = 1),
            candidate("long", 2.0, completed = 1, duration = 300_000L),
            candidate("short", 2.0, completed = 1),
            candidate("never", 5.0),
        ), query)
        assertEquals(listOf("both", "long"), result.map { it.recordingId })
    }

    @Test fun notUnknownMustNotMatchAndExplicitMissingCanBeQueried() {
        val notLow = SmartPlaylistV2Query(
            rules = SmartPlaylistRule.Not(check(
                SmartPlaylistRuleField.EXPLICIT_RATING,
                SmartPlaylistRuleOperator.LESS_THAN, 3.0,
            )),
        )
        assertEquals(listOf("good"), planner.preview(listOf(
            candidate("missing", rating = null),
            candidate("bad", rating = 2.0),
            candidate("good", rating = 5.0),
        ), notLow).map { it.recordingId })
        val missing = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.IS_MISSING),
        )
        assertEquals(listOf("missing"), planner.preview(
            listOf(candidate("missing"), candidate("rated", 1.0)), missing,
        ).map { it.recordingId })
    }

    @Test fun neverPlayedIsZeroCompletionsNotAncientLastPlay() {
        val never = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.EQUAL, 0.0),
        )
        assertEquals(listOf("never"), planner.preview(listOf(
            candidate("never", completed = 0),
            candidate("old", completed = 1, lastAudible = 1L),
        ), never).map { it.recordingId })
        val old = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 30.0),
        )
        assertEquals(listOf("old"), planner.preview(listOf(
            candidate("never", completed = 0),
            candidate("old", completed = 1, lastAudible = 1L),
        ), old).map { it.recordingId })
    }

    @Test fun identicalSourcesDeduplicateButConflictingSourcesAreRejected() {
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
        )
        val a = candidate("rec:a", rating = 2.0, completed = 1)
        assertEquals(listOf("rec:a"), planner.preview(listOf(
            a, a, candidate("rec:x", 5.0, completed = 1),
            candidate("rec:x", 4.0, completed = 1),
        ), query).map { it.recordingId })
    }

    @Test fun sortingAndLimitWorkBeyondOldOneHundredLimit() {
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0),
            sort = SmartPlaylistV2Sort.COMPLETIONS_ASC,
            limit = 150,
        )
        val tracks = (1..160).map { candidate("rec:%03d".format(it), completed = it.toLong()) }
        val result = planner.preview(tracks, query)
        assertEquals(150, result.size)
        assertEquals("rec:001", result.first().recordingId)
        assertEquals("rec:150", result.last().recordingId)
    }

    @Test fun moreThanFiveThousandCandidatesDoesNotBecomesFalseEmpty() {
        val tracks = (1..5_001).map { candidate("rec:%05d".format(it), completed = 1) }
        val query = SmartPlaylistV2Query(
            rules = check(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleOperator.GREATER_OR_EQUAL, 1.0), limit = 10,
        )
        assertEquals(10, planner.preview(tracks, query).size)
    }

    @Test fun invalidValueCombinationsAndEmptyGroupsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(rules = check(
                SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.GREATER_THAN,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartPlaylistV2Query(rules = check(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleOperator.IS_PRESENT, 3.0,
            ))
        }
        assertTrue(planner.preview(listOf(candidate("unavailable", 5.0, available = false)),
            SmartPlaylistV2Query(rules = check(
                SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleOperator.IS_PRESENT,
            ))).isEmpty())
    }
}
