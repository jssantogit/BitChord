package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED contracts: no rediscovery implementation is present at this commit. */
class LibraryRediscoveryPreviewPlannerTest {
    private val planner = LibraryRediscoveryPreviewPlanner(nowEpochMs = { NOW })

    @Test
    fun `empty input returns no rediscovery and never invents history`() {
        assertTrue(planner.preview(emptyList()).isEmpty())
        val neverHeard = candidate("never", lastAudible = null, completions = 1, rating = 5.0)
        val startOnly = candidate("start-only", lastAudible = null, completions = 0, starts = 4, rating = 5.0)
        assertTrue(planner.preview(listOf(neverHeard, startOnly)).isEmpty())
    }

    @Test
    fun `positive rating alone does not prove audible completion`() {
        val candidates = listOf(
            candidate("five-unplayed", rating = 5.0, completions = 0),
            candidate("four-played", rating = 4.0, completions = 1),
            candidate("neutral-one", rating = 3.0, completions = 1),
            candidate("unrated-one", rating = null, completions = 1),
        )
        assertEquals(listOf("four-played"), planner.preview(candidates).map { it.recordingId })
    }

    @Test
    fun `behavior only path requires two completions and manual or replay selection`() {
        val input = listOf(
            candidate("two-auto", rating = null, completions = 2),
            candidate("two-manual", rating = null, completions = 2, manualSelections = 1),
            candidate("two-replay", rating = 3.0, completions = 2, replays = 1),
            candidate("one-manual", rating = null, completions = 1, manualSelections = 5),
            candidate("neutral-auto", rating = 3.0, completions = 3),
        )
        assertEquals(listOf("two-manual", "two-replay"), planner.preview(input).map { it.recordingId })
        assertTrue(planner.preview(input).all {
            RediscoveryReason.CONFIRMED_POSITIVE_BEHAVIOR in it.reasons &&
                RediscoveryReason.LONG_ABSENCE in it.reasons
        })
    }

    @Test
    fun `negative rating and predominant early skips veto all positive paths`() {
        val input = listOf(
            candidate("negative", rating = 2.5, completions = 9, manualSelections = 5),
            candidate("skip-heavy", rating = 5.0, completions = 1, lateSkips = 1, earlySkips = 3),
            candidate("not-heavy", rating = 4.0, completions = 1, lateSkips = 1, earlySkips = 2),
            candidate("nan", rating = Double.NaN, completions = 1),
            candidate("infinity", rating = Double.POSITIVE_INFINITY, completions = 1),
        )
        assertEquals(listOf("not-heavy"), planner.preview(input).map { it.recordingId })
    }

    @Test
    fun `exactly thirty complete days is eligible and one millisecond earlier is not`() {
        val exact = candidate("exact", lastAudible = NOW - THIRTY_DAYS)
        val tooSoon = candidate("too-soon", lastAudible = NOW - THIRTY_DAYS + 1L)
        val future = candidate("future", lastAudible = NOW + 1L)
        val invalid = candidate("invalid", lastAudible = -1L)
        assertEquals(listOf("exact"), planner.preview(listOf(tooSoon, exact, future, invalid)).map { it.recordingId })
    }

    @Test
    fun `start-only interaction within seven days blocks forgotten preview but never creates dislike`() {
        val recentStart = candidate(
            "recent-start",
            starts = 2,
            lastEvent = NOW - SEVEN_DAYS + 1L,
            lastAudible = NOW - FORTY_DAYS,
        )
        val sevenDaysExactly = candidate(
            "exact-seven",
            lastEvent = NOW - SEVEN_DAYS,
            lastAudible = NOW - FORTY_DAYS,
        )
        val recentCompletion = candidate(
            "recent-terminal",
            lastEvent = NOW - SEVEN_DAYS + 1L,
            lastAudible = NOW - FORTY_DAYS,
        )
        assertEquals(listOf("exact-seven"), planner.preview(listOf(recentStart, sevenDaysExactly, recentCompletion)).map { it.recordingId })
        assertFalse(planner.preview(listOf(recentStart)).any { RediscoveryReason.CONFIRMED_POSITIVE_BEHAVIOR in it.reasons })
    }

    @Test
    fun `invalid interaction date fails closed instead of inventing old history`() {
        val input = listOf(
            candidate("future-interaction", lastEvent = NOW + 1L),
            candidate("negative-interaction", lastEvent = -1L),
            candidate("unknown-interaction", lastEvent = null),
        )
        assertEquals(listOf("unknown-interaction"), planner.preview(input).map { it.recordingId })
    }

    @Test
    fun `sorts by oldest audible then downstream score then current rank and id`() {
        val input = listOf(
            candidate("score-low", score = 30.0, lastAudible = NOW - FORTY_DAYS),
            candidate("score-high", score = 70.0, lastAudible = NOW - FORTY_DAYS),
            candidate("oldest", score = 0.0, lastAudible = NOW - 90L * DAY),
            candidate("tie-second", score = 70.0, lastAudible = NOW - FORTY_DAYS),
        )
        val preview = planner.preview(input)
        assertEquals(listOf("oldest", "score-high", "tie-second", "score-low"), preview.map { it.recordingId })
        assertEquals(listOf(3, 2, 4, 1), preview.map { it.rankPosition })
        assertEquals(90L * DAY, preview.first().timeSinceLastAudibleMs)
    }

    @Test
    fun `filters full unsliced rankings before preview limit and leaves main order intact`() {
        val input = (0 until 13).map { index ->
            candidate(
                "rec:$index",
                rating = if (index < 8) 3.0 else 4.0,
                completions = if (index < 8) 0 else 1,
                lastAudible = if (index < 8) null else NOW - (50L + index) * DAY,
                score = 100.0 - index,
            )
        }
        val snapshot = input.toList()
        val preview = planner.preview(input)
        assertEquals(5, preview.size)
        assertEquals(listOf("rec:12", "rec:11", "rec:10", "rec:9", "rec:8"), preview.map { it.recordingId })
        assertEquals(snapshot, input)
        assertEquals(snapshot.map { it.base.base.recordingId }, input.map { it.base.base.recordingId })
        assertTrue(preview.all { it.rankPosition > 5 })
    }

    @Test
    fun `output holds original candidate scores explanations and timestamps without mutation`() {
        val full = candidate(
            "known",
            score = 43.0,
            rating = 5.0,
            lastAudible = NOW - FORTY_DAYS,
            lastEvent = NOW - FORTY_DAYS,
            completions = 2,
            manualSelections = 1,
        )
        val first = planner.preview(listOf(full)).single()
        val second = planner.preview(listOf(full)).single()
        assertSame(full, first.source)
        assertEquals(43.0, first.finalScore, 0.0)
        assertEquals(NOW - FORTY_DAYS, first.lastAudibleListenAtEpochMs)
        assertEquals(NOW - FORTY_DAYS, first.lastInteractionAtEpochMs)
        assertEquals(
            setOf(
                RediscoveryReason.POSITIVE_EXPLICIT_PREFERENCE,
                RediscoveryReason.CONFIRMED_POSITIVE_BEHAVIOR,
                RediscoveryReason.LONG_ABSENCE,
            ),
            first.reasons,
        )
        assertEquals(first, second)
        assertTrue(first.source.explorationReasons.isEmpty())
    }

    @Test
    fun `exploration promotion cannot independently make a recording eligible`() {
        val unplayed = candidate("explored", rating = null, completions = 0).copy(
            explorationReasons = setOf(ExplorationReason.UNDEREXPOSED_PROMOTION),
        )
        val historical = candidate("familiar", rating = 4.5, completions = 1).copy(
            explorationReasons = setOf(ExplorationReason.UNDEREXPOSED_PROMOTION),
        )
        assertEquals(listOf("familiar"), planner.preview(listOf(unplayed, historical)).map { it.recordingId })
        assertTrue(ExplorationReason.UNDEREXPOSED_PROMOTION in planner.preview(listOf(historical)).single().source.explorationReasons)
    }

    @Test
    fun `duplicate canonical recording IDs or corrupt feature identities fail clearly`() {
        val one = candidate("same")
        assertTrue(runCatching { planner.preview(listOf(one, one)) }.exceptionOrNull() is IllegalArgumentException)
        val mismatched = one.copy(
            diversity = one.diversity.copy(
                base = one.base.copy(base = one.base.base.copy(features = one.base.base.features.copy(recordingId = "wrong"))),
            ),
        )
        assertTrue(runCatching { planner.preview(listOf(mismatched)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `policy accepts bounded preview sizes and rejects unbounded values`() {
        assertTrue(runCatching { LibraryRediscoveryPreviewPolicy(maxResults = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LibraryRediscoveryPreviewPolicy(maxResults = 11) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LibraryRediscoveryPreviewPolicy(minimumAbsenceMs = 0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LibraryRediscoveryPreviewPolicy(recentInteractionWindowMs = 0L) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, LibraryRediscoveryPreviewPlanner(
            policy = LibraryRediscoveryPreviewPolicy(maxResults = 2),
            nowEpochMs = { NOW },
        ).preview((0 until 7).map { candidate("id:$it") }).size)
    }

    private fun candidate(
        id: String,
        score: Double = 30.0,
        rating: Double? = 4.0,
        starts: Long = 1L,
        completions: Long = 1L,
        earlySkips: Long = 0L,
        lateSkips: Long = 0L,
        manualSelections: Int = 0,
        replays: Int = 0,
        lastAudible: Long? = NOW - FORTY_DAYS,
        lastEvent: Long? = NOW - FORTY_DAYS,
    ): ExplorationRankedCandidate {
        val behavior = RecordingBehaviorSummary(
            recordingId = id,
            starts = starts,
            completions = completions,
            earlySkips = earlySkips,
            lateSkips = lateSkips,
            interruptions = 0L,
            totalListenedMs = 180_000L,
            lastEventAtEpochMs = lastEvent,
        )
        val features = RecordingRecommendationFeatures(
            recordingId = id,
            explicitRating = rating,
            explicitRatedAtEpochMs = null,
            lastRatingChangedAtEpochMs = null,
            lastRatingChangeDirection = null,
            behaviorSummary = behavior,
            manualSelections = manualSelections,
            replays = replays,
            lastAudibleListenAtEpochMs = lastAudible,
        )
        val components = RecommendationScoreComponents(
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
        )
        val ranked = RankedRecordingCandidate(id, score, components, emptySet(), features)
        val contextual = SessionContextRankedCandidate(ranked, 0.0, score, emptySet())
        return ExplorationRankedCandidate(
            diversity = DiversityRankedCandidate(contextual, 0.0, score, emptySet(), emptySet()),
            originalRank = 1,
        )
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
        const val THIRTY_DAYS = 30L * DAY
        const val SEVEN_DAYS = 7L * DAY
        const val FORTY_DAYS = 40L * DAY
        const val NOW = 100L * DAY
    }
}
