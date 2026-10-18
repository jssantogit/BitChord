package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledExplorationRerankerTest {
    private val reranker = ControlledExplorationReranker()

    @Test
    fun `empty and short lists do not promote or lose candidates`() {
        assertTrue(reranker.rerank(emptyList()).isEmpty())
        for (size in 1..5) {
            val upstream = entries(size)
            val result = reranker.rerank(upstream)
            assertEquals(upstream, result.map { it.diversity })
            assertTrue(result.all { it.explorationReasons.isEmpty() })
        }
    }

    @Test
    fun `at most one underexposed candidate enters position five without changing protected top three`() {
        val upstream = entries(14, underexposed = setOf(6, 7))
        val result = reranker.rerank(upstream)
        assertEquals(listOf("r0", "r1", "r2"), result.take(3).map { it.base.base.recordingId })
        assertEquals("r6", result[4].base.base.recordingId)
        assertEquals(setOf(ExplorationReason.UNDEREXPOSED_PROMOTION), result[4].explorationReasons)
        assertEquals(7, result[4].promotedFromRank)
        assertEquals(5, result[4].promotedToRank)
        assertEquals(1, result.count { it.explorationReasons.isNotEmpty() })
        assertEquals(upstream.toSet(), result.map { it.diversity }.toSet())
        assertEquals(upstream.map { it.base.base.recordingId }.toSet(), result.map { it.base.base.recordingId }.toSet())
        assertEquals(result.size, result.map { it.base.base.recordingId }.distinct().size)
        assertEquals(upstream[6], result[4].diversity)
        assertEquals(upstream[6].finalScore, result[4].finalScore, 0.0)
        assertEquals(upstream[6].base.base.components, result[4].base.base.components)
    }

    @Test
    fun `negative or positive ratings completions and replays prevent discovery labeling`() {
        val upstream = entries(12, underexposed = emptySet()).toMutableList()
        upstream[6] = candidate(6, explicitRating = 2.0)
        upstream[7] = candidate(7, explicitRating = 4.0)
        upstream[8] = candidate(8, completions = 1)
        upstream[9] = candidate(9, replays = 1)
        upstream[10] = candidate(10, manualSelections = 2)
        val result = reranker.rerank(upstream)
        assertEquals(upstream, result.map { it.diversity })
        assertTrue(result.all { it.explorationReasons.isEmpty() })
    }

    @Test
    fun `early skip heavy tracks are not promoted but started only is not negative`() {
        val upstream = entries(10).toMutableList()
        upstream[6] = candidate(6, earlySkips = 3, lateSkips = 1)
        upstream[7] = candidate(7, starts = 1, explicitRating = null)
        val result = reranker.rerank(upstream)
        assertEquals("r7", result[4].base.base.recordingId)
        assertEquals(setOf(ExplorationReason.UNDEREXPOSED_PROMOTION), result[4].explorationReasons)
    }

    @Test
    fun `large score gap and candidates beyond bounded window do not get promoted`() {
        val largeGap = entries(12, underexposed = setOf(10), scoreStep = 3.0)
        assertEquals(largeGap, reranker.rerank(largeGap).map { it.diversity })
        val far = entries(40, underexposed = setOf(25), scoreStep = 0.25)
        assertEquals(far, reranker.rerank(far).map { it.diversity })
        assertTrue(reranker.rerank(far).all { it.explorationReasons.isEmpty() })
    }

    @Test
    fun `tie break is score then original position and reranking is deterministic`() {
        val upstream = entries(12, underexposed = setOf(6, 7, 8), scoreStep = 0.0)
        val first = reranker.rerank(upstream)
        assertEquals("r6", first[4].base.base.recordingId)
        assertEquals(first, reranker.rerank(upstream))
        assertEquals(first, reranker.rerank(upstream))
        assertFalse(first.any { it.promotedFromRank != null && it.explorationReasons.isEmpty() })
    }

    @Test
    fun `already underexposed target and positive preference target are protected`() {
        val targetNovel = entries(10, underexposed = setOf(4, 6))
        assertEquals(targetNovel, reranker.rerank(targetNovel).map { it.diversity })
        val favoriteTarget = entries(10, underexposed = setOf(6)).toMutableList()
        favoriteTarget[4] = candidate(4, explicitRating = 5.0)
        assertEquals(favoriteTarget, reranker.rerank(favoriteTarget).map { it.diversity })
    }

    @Test
    fun `policy rejects unbounded and invalid parameters`() {
        assertTrue(runCatching { ControlledExplorationPolicy(protectedTopPositions = 5) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ControlledExplorationPolicy(lookahead = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ControlledExplorationPolicy(maximumScoreGap = -1.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ControlledExplorationPolicy(maximumScoreGap = Double.NaN) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun entries(
        count: Int,
        underexposed: Set<Int> = emptySet(),
        scoreStep: Double = 1.0,
    ) = (0 until count).map { index ->
        candidate(
            index = index,
            score = 100.0 - index * scoreStep,
            explicitRating = if (index in underexposed) null else if (index == 4) 3.0 else 4.0,
            completions = if (index == 4 && index !in underexposed) 1 else 0,
        )
    }

    private fun candidate(
        index: Int,
        score: Double = 100.0 - index,
        explicitRating: Double? = null,
        starts: Int = 0,
        completions: Int = 0,
        earlySkips: Int = 0,
        lateSkips: Int = 0,
        replays: Int = 0,
        manualSelections: Int = 0,
    ): DiversityRankedCandidate {
        val recordingId = "r" + index
        val ranked = RankedRecordingCandidate(
            recordingId = recordingId,
            score = score,
            components = RecommendationScoreComponents(
                explicitPreference = score,
                completionEvidence = 0.0,
                earlySkipPenalty = 0.0,
                lateSkipPenalty = 0.0,
                manualSelectionEvidence = 0.0,
                replayEvidence = 0.0,
                behavioralEvidence = 0.0,
                recentListeningCooldown = 0.0,
                recentRatingChange = 0.0,
                total = score,
            ),
            reasons = emptySet(),
            features = RecordingRecommendationFeatures(
                recordingId = recordingId,
                explicitRating = explicitRating,
                explicitRatedAtEpochMs = null,
                lastRatingChangedAtEpochMs = null,
                lastRatingChangeDirection = null,
                behaviorSummary = RecordingBehaviorSummary(
                    recordingId = recordingId,
                    starts = starts.toLong(),
                    completions = completions.toLong(),
                    earlySkips = earlySkips.toLong(),
                    lateSkips = lateSkips.toLong(),
                    interruptions = 0,
                    totalListenedMs = 0L,
                    lastEventAtEpochMs = null,
                ),
                manualSelections = manualSelections,
                replays = replays,
                lastAudibleListenAtEpochMs = null,
            ),
        )
        return DiversityRankedCandidate(
            base = SessionContextRankedCandidate(ranked, 0.0, score, emptySet()),
            diversityAdjustment = 0.0,
            finalScore = score,
            diversityReasons = emptySet(),
            overlappingArtistIds = emptySet(),
        )
    }
}
