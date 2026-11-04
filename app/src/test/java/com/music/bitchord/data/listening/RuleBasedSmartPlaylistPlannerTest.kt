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

    @Test fun negativeRatingsAndUnknownDurationDoNotPassConservativeFilters() {
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
        assertEquals(listOf("rec:ok"), result.map { it.recordingId })
        assertTrue(RuleBasedInclusionReason.CONFIRMED_COMPLETION in result.single().reasons)
        assertTrue(RuleBasedInclusionReason.TRUSTED_DURATION in result.single().reasons)
    }
}
