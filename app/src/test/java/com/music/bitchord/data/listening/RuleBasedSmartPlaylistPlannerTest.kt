package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedSmartPlaylistPlannerTest {
    private val now = 50L * 86_400_000L
    private val planner = RuleBasedSmartPlaylistPlanner(nowEpochMs = { now })
    private fun row(
        id: String,
        rating: Double? = null,
        completed: Long = 0,
        audibleAt: Long? = null,
        duration: Long? = 180_000L,
        available: Boolean = true,
    ) = RuleBasedRecordingCandidate(id, rating, completed, audibleAt, duration, available)

    @Test fun stableCanonicalOrderingAndDuplicatesWithConflictingEvidenceFailClosed() {
        val q = RuleBasedSmartPlaylistQuery(minimumExplicitRating = 4.0, limit = 2)
        val result = planner.preview(
            listOf(
                row("rec:z", 4.5),
                row("rec:b", 5.0),
                row("rec:a", 5.0),
                row("rec:x", 5.0),
                row("rec:x", 4.0),
                row("rec:offline", 5.0, available = false),
            ), q,
        )
        assertEquals(listOf("rec:a", "rec:b"), result.map { it.recordingId })
        assertTrue(result.all { RuleBasedInclusionReason.EXPLICIT_RATING in it.reasons })
    }

    @Test fun startOnlyAndMissingAudibleHistoryNeverInventsForgottenTracks() {
        val q = RuleBasedSmartPlaylistQuery(minimumDaysSinceAudible = 30)
        val results = planner.preview(
            listOf(
                row("rec:start", completed = 0, audibleAt = null),
                row("rec:real", completed = 1, audibleAt = 5L * 86_400_000L),
                row("rec:recent", completed = 1, audibleAt = 45L * 86_400_000L),
            ), q,
        )
        assertEquals(listOf("rec:real"), results.map { it.recordingId })
    }

    @Test fun negativeRatingsWithoutRatingRuleStillParticipateAndUnknownDurationIsExcluded() {
        val q = RuleBasedSmartPlaylistQuery(
            minimumCompletions = 1,
            minimumDurationMs = 100_000L,
        )
        val result = planner.preview(
            listOf(
                row("rec:negative", 2.0, completed = 4),
                row("rec:unknown", 5.0, completed = 1, duration = null),
                row("rec:ok", 4.0, completed = 1, duration = 150_000L),
            ), q,
        )
        assertEquals(listOf("rec:ok", "rec:negative"), result.map { it.recordingId })
        assertTrue(result.all { RuleBasedInclusionReason.CONFIRMED_COMPLETION in it.reasons })
        assertTrue(result.all { RuleBasedInclusionReason.TRUSTED_DURATION in it.reasons })
    }
    @Test fun onlyExplicitRulesMayExcludeLowRatings() {
        val durationOnly = RuleBasedSmartPlaylistQuery(minimumDurationMs = 100_000L)
        val tracks = listOf(row("one-star", 1.0), row("two-stars", 2.0), row("no-rating"))
        assertEquals(3, planner.preview(tracks, durationOnly).size)

        val highRated = RuleBasedSmartPlaylistQuery(minimumExplicitRating = 4.0)
        assertTrue(planner.preview(tracks, highRated).isEmpty())
    }

    @Test fun moreThanFiveThousandCanonicalCandidatesNeverMeansEmptyByDefault() {
        val tracks = (1..5_001).map { row("rec:%05d".format(it), completed = 1) }
        val query = RuleBasedSmartPlaylistQuery(minimumCompletions = 1, limit = 50)
        val results = planner.preview(tracks, query)
        assertEquals(50, results.size)
        assertEquals("rec:00001", results.first().recordingId)
    }

}
