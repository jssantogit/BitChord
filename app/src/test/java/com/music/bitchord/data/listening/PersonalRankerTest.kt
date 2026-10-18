package com.music.bitchord.data.listening

import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRankerTest {
    private val policy = PersonalRankerPolicy()

    @Test
    fun noRatingAndNoBehaviorStartsNeutral() = runTest {
        val result = ranker().rank(candidates("neutral")).single()

        assertEquals("neutral", result.recordingId)
        assertEquals(0.0, result.score, 0.0)
        assertEquals(0.0, result.components.explicitPreference, 0.0)
        assertEquals(0.0, result.components.behavioralEvidence, 0.0)
        assertEquals(0.0, result.components.recentListeningCooldown, 0.0)
        assertEquals(0.0, result.components.recentRatingChange, 0.0)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun explicitRatingUsesApprovedNonLinearPreferenceOrder() = runTest {
        val ranker = ranker(
            ratings = mapOf(
                "favorite" to ratingSignal("favorite", 5.0),
                "former-favorite" to ratingSignal("former-favorite", 4.5),
                "good" to ratingSignal("good", 4.0),
                "neutral" to ratingSignal("neutral", 3.0),
                "negative" to ratingSignal("negative", 2.0),
            ),
        )

        val ranked = ranker.rank(
            candidates("negative", "neutral", "good", "former-favorite", "favorite"),
        )

        assertEquals(
            listOf("favorite", "former-favorite", "good", "neutral", "negative"),
            ranked.map { it.recordingId },
        )
        assertTrue(ranked[0].components.explicitPreference > ranked[1].components.explicitPreference)
        assertTrue(ranked[1].components.explicitPreference > ranked[2].components.explicitPreference)
        assertTrue(ranked[2].components.explicitPreference > ranked[3].components.explicitPreference)
        assertTrue(ranked[4].components.explicitPreference < 0.0)
        assertTrue(RecommendationReason.EXPLICIT_FAVORITE in ranked[0].reasons)
        assertTrue(RecommendationReason.STRONG_EXPLICIT_PREFERENCE in ranked[1].reasons)
        assertTrue(RecommendationReason.NEGATIVE_EXPLICIT_PREFERENCE in ranked[4].reasons)
    }

    @Test
    fun negativeRatingStaysNegativeDespiteHugeRawStartCount() = runTest {
        val ranker = ranker(
            ratings = mapOf("negative" to ratingSignal("negative", 2.0)),
            behavior = mapOf(
                "negative" to behaviorSummary("negative", starts = 1_000_000),
            ),
        )

        val result = ranker.rank(candidates("negative")).single()

        assertTrue(result.score < 0.0)
        assertEquals(0.0, result.components.behavioralEvidence, 0.0)
    }

    @Test
    fun strongExplicitPreferenceDominatesAmbiguousBehavior() = runTest {
        val ranker = ranker(
            ratings = mapOf("favorite" to ratingSignal("favorite", 5.0)),
            behavior = mapOf(
                "favorite" to behaviorSummary("favorite", earlySkips = 100),
                "unrated" to behaviorSummary("unrated", completions = 100),
            ),
        )

        val ranked = ranker.rank(candidates("unrated", "favorite"))

        assertEquals("favorite", ranked.first().recordingId)
    }

    @Test
    fun completionHeavyBeatsEarlySkipHeavyAndEarlySkipPenalizesMoreThanLateSkip() = runTest {
        val ranker = ranker(
            behavior = mapOf(
                "completed" to behaviorSummary("completed", completions = 3),
                "early" to behaviorSummary("early", earlySkips = 3),
                "late" to behaviorSummary("late", lateSkips = 3),
            ),
        )

        val ranked = ranker.rank(candidates("early", "late", "completed"))
        val completed = ranked.single { it.recordingId == "completed" }
        val early = ranked.single { it.recordingId == "early" }
        val late = ranked.single { it.recordingId == "late" }

        assertEquals("completed", ranked.first().recordingId)
        assertTrue(early.score < late.score)
        assertTrue(abs(early.components.earlySkipPenalty) > abs(late.components.lateSkipPenalty))
        assertTrue(RecommendationReason.COMPLETION_HEAVY in completed.reasons)
        assertTrue(RecommendationReason.EARLY_SKIP_HEAVY in early.reasons)
    }

    @Test
    fun startsAndInterruptionsAloneRemainNeutral() = runTest {
        val ranker = ranker(
            behavior = mapOf(
                "noisy" to behaviorSummary(
                    recordingId = "noisy",
                    starts = 50,
                    interruptions = 20,
                ),
            ),
        )

        val result = ranker.rank(candidates("noisy")).single()

        assertEquals(0.0, result.score, 0.0)
        assertEquals(0.0, result.components.behavioralEvidence, 0.0)
    }

    @Test
    fun manualSelectionAndReplayArePositiveButBoundedAndAutomaticSelectionIsNotEquivalent() = runTest {
        val manualEvents = List(100) { index ->
            startEvent("manual", "manual-$index", SelectionIntent.MANUAL)
        }
        val replayEvents = List(100) { index ->
            startEvent("replay", "replay-$index", SelectionIntent.REPLAY)
        }
        val automaticEvents = List(100) { index ->
            startEvent("automatic", "automatic-$index", SelectionIntent.AUTOMATIC)
        }
        val ranker = ranker(
            recentEvents = mapOf(
                "manual" to manualEvents,
                "replay" to replayEvents,
                "automatic" to automaticEvents,
            ),
        )

        val ranked = ranker.rank(candidates("automatic", "manual", "replay"))
        val manual = ranked.single { it.recordingId == "manual" }
        val replay = ranked.single { it.recordingId == "replay" }
        val automatic = ranked.single { it.recordingId == "automatic" }

        assertTrue(manual.components.manualSelectionEvidence > 0.0)
        assertTrue(manual.components.manualSelectionEvidence <= policy.maxManualSelectionBoost)
        assertTrue(replay.components.replayEvidence > 0.0)
        assertTrue(replay.components.replayEvidence <= policy.maxReplayBoost)
        assertEquals(0.0, automatic.components.manualSelectionEvidence, 0.0)
        assertEquals(0.0, automatic.components.replayEvidence, 0.0)
        assertTrue(RecommendationReason.MANUAL_SELECTION_SIGNAL in manual.reasons)
        assertTrue(RecommendationReason.REPLAY_SIGNAL in replay.reasons)
    }

    @Test
    fun recentFourPointFiveRanksBelowEquivalentFourPointFiveOutsideCooldown() = runTest {
        val recentAt = NOW - ONE_HOUR_MS
        val oldAt = NOW - policy.cooldownWindowMs - ONE_HOUR_MS
        val ranker = ranker(
            ratings = mapOf(
                "recent" to ratingSignal("recent", 4.5),
                "old" to ratingSignal("old", 4.5),
            ),
            recentEvents = mapOf(
                "recent" to listOf(audibleTerminalEvent("recent", recentAt)),
                "old" to listOf(audibleTerminalEvent("old", oldAt)),
            ),
        )

        val ranked = ranker.rank(candidates("recent", "old"))
        val recent = ranked.single { it.recordingId == "recent" }
        val old = ranked.single { it.recordingId == "old" }

        assertEquals("old", ranked.first().recordingId)
        assertTrue(recent.components.recentListeningCooldown < 0.0)
        assertEquals(0.0, old.components.recentListeningCooldown, 0.0)
        assertTrue(recent.score > 0.0)
        assertTrue(RecommendationReason.RECENT_LISTEN_COOLDOWN in recent.reasons)
    }

    @Test
    fun cooldownDisappearsWithTime() = runTest {
        val listenedAt = NOW - ONE_HOUR_MS
        val ratings = mapOf("track" to ratingSignal("track", 4.5))
        val events = mapOf("track" to listOf(audibleTerminalEvent("track", listenedAt)))

        val duringCooldown = ranker(
            ratings = ratings,
            recentEvents = events,
            nowEpochMs = NOW,
        ).rank(candidates("track")).single()
        val afterCooldown = ranker(
            ratings = ratings,
            recentEvents = events,
            nowEpochMs = listenedAt + policy.cooldownWindowMs + ONE_HOUR_MS,
        ).rank(candidates("track")).single()

        assertTrue(duringCooldown.components.recentListeningCooldown < 0.0)
        assertEquals(0.0, afterCooldown.components.recentListeningCooldown, 0.0)
        assertTrue(afterCooldown.score > duringCooldown.score)
    }

    @Test
    fun startOnlyDoesNotTriggerRecentListeningCooldown() = runTest {
        val ranker = ranker(
            ratings = mapOf("remote" to ratingSignal("remote", 4.5)),
            recentEvents = mapOf(
                "remote" to listOf(
                    startEvent(
                        recordingId = "remote",
                        eventId = "start",
                        intent = SelectionIntent.AUTOMATIC,
                        occurredAtEpochMs = NOW - 60_000L,
                    ),
                ),
            ),
        )

        val result = ranker.rank(candidates("remote")).single()

        assertEquals(0.0, result.components.recentListeningCooldown, 0.0)
    }

    @Test
    fun recentRatingIncreaseAndDecreaseAreSmallBoundedTransientSignals() = runTest {
        val changedAt = NOW - ONE_DAY_MS
        val ranker = ranker(
            ratings = mapOf(
                "increased" to ratingSignal(
                    recordingId = "increased",
                    currentRating = 4.0,
                    changedAtEpochMs = changedAt,
                    direction = RecordingRatingDirection.INCREASED,
                ),
                "decreased" to ratingSignal(
                    recordingId = "decreased",
                    currentRating = 4.0,
                    changedAtEpochMs = changedAt,
                    direction = RecordingRatingDirection.DECREASED,
                ),
            ),
        )

        val ranked = ranker.rank(candidates("decreased", "increased"))
        val increased = ranked.single { it.recordingId == "increased" }
        val decreased = ranked.single { it.recordingId == "decreased" }

        assertTrue(increased.score > decreased.score)
        assertTrue(abs(increased.components.recentRatingChange) <= policy.maxRecentRatingChangeAdjustment)
        assertTrue(abs(decreased.components.recentRatingChange) <= policy.maxRecentRatingChangeAdjustment)
        assertTrue(abs(increased.components.recentRatingChange) < abs(increased.components.explicitPreference))
        assertTrue(RecommendationReason.RECENT_RATING_INCREASE in increased.reasons)
        assertTrue(RecommendationReason.RECENT_RATING_DECREASE in decreased.reasons)
    }

    @Test
    fun clearedRatingDoesNotReuseHistoricalExplicitValue() = runTest {
        val ranker = ranker(
            ratings = mapOf(
                "cleared" to ExplicitRecordingRatingSignal(
                    recordingId = "cleared",
                    currentRating = null,
                    currentRatedAtEpochMs = null,
                    lastChangedAtEpochMs = NOW - ONE_DAY_MS,
                    lastChangeDirection = RecordingRatingDirection.CLEARED,
                    hasExplicitFeedback = true,
                ),
            ),
        )

        val result = ranker.rank(candidates("cleared")).single()

        assertEquals(0.0, result.components.explicitPreference, 0.0)
        assertEquals(0.0, result.components.recentRatingChange, 0.0)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun rankingIsDeterministicForTiesAndCanonicalCandidatesAreDeduplicated() = runTest {
        val ranker = ranker()

        val ranked = ranker.rank(
            candidates("zeta", "alpha", "zeta", "beta", "alpha"),
        )

        assertEquals(listOf("alpha", "beta", "zeta"), ranked.map { it.recordingId })
    }

    @Test
    fun scoreAndEveryComponentRemainFiniteAndBoundedUnderExtremeEvidence() = runTest {
        val ranker = ranker(
            ratings = mapOf("extreme" to ratingSignal("extreme", 5.0)),
            behavior = mapOf(
                "extreme" to RecordingBehaviorSummary(
                    recordingId = "extreme",
                    starts = Long.MAX_VALUE,
                    completions = Long.MAX_VALUE,
                    earlySkips = Long.MAX_VALUE,
                    lateSkips = Long.MAX_VALUE,
                    interruptions = Long.MAX_VALUE,
                    totalListenedMs = Long.MAX_VALUE,
                    lastEventAtEpochMs = NOW,
                ),
            ),
            recentEvents = mapOf(
                "extreme" to List(100) { index ->
                    startEvent("extreme", "event-$index", SelectionIntent.REPLAY)
                },
            ),
        )

        val result = ranker.rank(candidates("extreme")).single()
        val values = listOf(
            result.score,
            result.components.explicitPreference,
            result.components.completionEvidence,
            result.components.earlySkipPenalty,
            result.components.lateSkipPenalty,
            result.components.manualSelectionEvidence,
            result.components.replayEvidence,
            result.components.behavioralEvidence,
            result.components.recentListeningCooldown,
            result.components.recentRatingChange,
            result.components.total,
        )

        assertTrue(values.all(Double::isFinite))
        assertTrue(result.score in policy.minimumScore..policy.maximumScore)
    }

    private fun candidates(vararg recordingIds: String): List<RecommendationCandidate> =
        recordingIds.map(::RecommendationCandidate)

    private fun ranker(
        ratings: Map<String, ExplicitRecordingRatingSignal> = emptyMap(),
        behavior: Map<String, RecordingBehaviorSummary> = emptyMap(),
        recentEvents: Map<String, List<ListeningEvent>> = emptyMap(),
        nowEpochMs: Long = NOW,
    ): PersonalRanker = PersonalRanker(
        behaviorReader = FakeBehaviorReader(behavior, recentEvents),
        ratingReader = FakeRatingReader(ratings),
        policy = policy,
        nowEpochMs = { nowEpochMs },
    )

    private fun ratingSignal(
        recordingId: String,
        currentRating: Double,
        changedAtEpochMs: Long = NOW - THIRTY_DAYS_MS,
        direction: RecordingRatingDirection = RecordingRatingDirection.INITIAL_SET,
    ) = ExplicitRecordingRatingSignal(
        recordingId = recordingId,
        currentRating = currentRating,
        currentRatedAtEpochMs = changedAtEpochMs,
        lastChangedAtEpochMs = changedAtEpochMs,
        lastChangeDirection = direction,
        hasExplicitFeedback = true,
    )

    private fun behaviorSummary(
        recordingId: String,
        starts: Long = 0,
        completions: Long = 0,
        earlySkips: Long = 0,
        lateSkips: Long = 0,
        interruptions: Long = 0,
    ) = RecordingBehaviorSummary(
        recordingId = recordingId,
        starts = starts,
        completions = completions,
        earlySkips = earlySkips,
        lateSkips = lateSkips,
        interruptions = interruptions,
        totalListenedMs = 0,
        lastEventAtEpochMs = null,
    )

    private fun startEvent(
        recordingId: String,
        eventId: String,
        intent: SelectionIntent,
        occurredAtEpochMs: Long = NOW - ONE_DAY_MS,
    ) = ListeningEvent(
        id = eventId,
        sessionId = "session",
        recordingId = recordingId,
        mediaAssetId = null,
        assetProvider = null,
        occurredAtEpochMs = occurredAtEpochMs,
        type = ListeningEventType.PLAYBACK_STARTED,
        listenedDurationMs = null,
        recordingDurationMs = null,
        endCause = null,
        selectionIntent = intent,
        selectionOrigin = SelectionOrigin.LIBRARY,
        recommendationOrigin = null,
    )

    private fun audibleTerminalEvent(
        recordingId: String,
        occurredAtEpochMs: Long,
    ) = ListeningEvent(
        id = "terminal-$recordingId-$occurredAtEpochMs",
        sessionId = "session",
        recordingId = recordingId,
        mediaAssetId = null,
        assetProvider = null,
        occurredAtEpochMs = occurredAtEpochMs,
        type = ListeningEventType.PLAYBACK_COMPLETED,
        listenedDurationMs = 180_000L,
        recordingDurationMs = 180_000L,
        endCause = PlaybackEndCause.ENDED,
        selectionIntent = null,
        selectionOrigin = null,
        recommendationOrigin = null,
    )

    private class FakeBehaviorReader(
        private val summaries: Map<String, RecordingBehaviorSummary>,
        private val recentEvents: Map<String, List<ListeningEvent>>,
    ) : ListeningBehaviorReader {
        override suspend fun session(sessionId: String): ListeningSession? = null

        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> = emptyList()

        override suspend fun recentEventsForRecording(
            recordingId: String,
            limit: Int,
        ): List<ListeningEvent> = recentEvents[recordingId].orEmpty().take(limit)

        override suspend fun behaviorSummary(recordingId: String): RecordingBehaviorSummary =
            summaries[recordingId] ?: RecordingBehaviorSummary(
                recordingId = recordingId,
                starts = 0,
                completions = 0,
                earlySkips = 0,
                lateSkips = 0,
                interruptions = 0,
                totalListenedMs = 0,
                lastEventAtEpochMs = null,
            )
    }

    private class FakeRatingReader(
        private val signals: Map<String, ExplicitRecordingRatingSignal>,
    ) : ExplicitRecordingRatingReader {
        override suspend fun currentRating(recordingId: String): RecordingRating? = null

        override fun observeRating(recordingId: String): Flow<RecordingRating?> = flowOf(null)

        override suspend fun ratedRecordings(limit: Int): List<RecordingRating> = emptyList()

        override suspend fun explicitRatingSignal(recordingId: String): ExplicitRecordingRatingSignal =
            signals[recordingId] ?: ExplicitRecordingRatingSignal(
                recordingId = recordingId,
                currentRating = null,
                currentRatedAtEpochMs = null,
                lastChangedAtEpochMs = null,
                lastChangeDirection = null,
                hasExplicitFeedback = false,
            )
    }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
        const val ONE_DAY_MS = 24L * ONE_HOUR_MS
        const val THIRTY_DAYS_MS = 30L * ONE_DAY_MS
        const val NOW = 1_800_000_000_000L
    }
}
