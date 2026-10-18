package com.music.bitchord.data.listening

import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRankerTest {
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
            listOf("negative", "neutral", "good", "former-favorite", "favorite"),
        )

        assertEquals(
            listOf("favorite", "former-favorite", "good", "neutral", "negative"),
            ranked.map { it.recordingId },
        )
        assertTrue(ranked[0].components.explicitPreference > ranked[1].components.explicitPreference)
        assertTrue(ranked[1].components.explicitPreference > ranked[2].components.explicitPreference)
        assertTrue(ranked[2].components.explicitPreference > ranked[3].components.explicitPreference)
        assertTrue(ranked[4].components.explicitPreference < 0.0)
    }

    @Test
    fun completionPromotesWhileEarlySkipSuppressesMoreThanLateSkip() = runTest {
        val ranker = ranker(
            behavior = mapOf(
                "completed" to behaviorSummary("completed", completions = 3),
                "early" to behaviorSummary("early", earlySkips = 3),
                "late" to behaviorSummary("late", lateSkips = 3),
            ),
        )

        val completed = ranker.rank(listOf("completed")).single()
        val early = ranker.rank(listOf("early")).single()
        val late = ranker.rank(listOf("late")).single()

        assertTrue(completed.score > 0.0)
        assertTrue(early.score < late.score)
        assertTrue(abs(early.components.earlySkipPenalty) > abs(late.components.lateSkipPenalty))
        assertEquals(
            completed.score,
            completed.components.total,
            0.000_001,
        )
    }

    @Test
    fun startsAndInterruptionsAloneDoNotBecomePositiveOrNegativePreference() = runTest {
        val ranker = ranker(
            behavior = mapOf(
                "noisy" to behaviorSummary(
                    recordingId = "noisy",
                    starts = 50,
                    interruptions = 20,
                ),
            ),
        )

        val result = ranker.rank(listOf("noisy")).single()

        assertEquals(0.0, result.score, 0.0)
        assertEquals(0.0, result.components.completionEvidence, 0.0)
        assertEquals(0.0, result.components.earlySkipPenalty, 0.0)
        assertEquals(0.0, result.components.lateSkipPenalty, 0.0)
    }

    @Test
    fun recentFourPointFiveListeningGetsStrongerCooldownThanStableFourPointZero() = runTest {
        val recentAt = NOW - ONE_HOUR_MS
        val ranker = ranker(
            ratings = mapOf(
                "former-favorite" to ratingSignal("former-favorite", 4.5),
                "good" to ratingSignal("good", 4.0),
            ),
            recentEvents = mapOf(
                "former-favorite" to listOf(
                    terminalEvent(
                        recordingId = "former-favorite",
                        occurredAtEpochMs = recentAt,
                        type = ListeningEventType.PLAYBACK_COMPLETED,
                        listenedDurationMs = 180_000,
                    ),
                ),
            ),
        )

        val ranked = ranker.rank(listOf("former-favorite", "good"))
        val formerFavorite = ranked.single { it.recordingId == "former-favorite" }
        val good = ranked.single { it.recordingId == "good" }

        assertTrue(formerFavorite.components.recentListeningCooldown < 0.0)
        assertTrue(formerFavorite.score < good.score)
    }

    @Test
    fun startOnlyDoesNotTriggerRecentListeningCooldown() = runTest {
        val ranker = ranker(
            ratings = mapOf(
                "remote" to ratingSignal("remote", 4.5),
            ),
            recentEvents = mapOf(
                "remote" to listOf(
                    ListeningEvent(
                        id = "start",
                        sessionId = "session",
                        recordingId = "remote",
                        mediaAssetId = "asset",
                        assetProvider = "opensubsonic",
                        occurredAtEpochMs = NOW - 60_000,
                        type = ListeningEventType.PLAYBACK_STARTED,
                        listenedDurationMs = null,
                        recordingDurationMs = null,
                        endCause = null,
                        selectionIntent = SelectionIntent.AUTOMATIC,
                        selectionOrigin = SelectionOrigin.QUEUE,
                        recommendationOrigin = null,
                    ),
                ),
            ),
        )

        val result = ranker.rank(listOf("remote")).single()

        assertEquals(0.0, result.components.recentListeningCooldown, 0.0)
    }

    @Test
    fun recentRatingDirectionIsSmallAndTransient() = runTest {
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

        val ranked = ranker.rank(listOf("decreased", "increased"))
        val increased = ranked.single { it.recordingId == "increased" }
        val decreased = ranked.single { it.recordingId == "decreased" }

        assertTrue(increased.score > decreased.score)
        assertTrue(abs(increased.components.ratingMomentum) <= 5.0)
        assertTrue(abs(decreased.components.ratingMomentum) <= 5.0)
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

        val result = ranker.rank(listOf("cleared")).single()

        assertEquals(0.0, result.components.explicitPreference, 0.0)
        assertEquals(0.0, result.components.ratingMomentum, 0.0)
    }

    @Test
    fun rankingIsDeterministicForTiesAndCanonicalCandidatesAreDeduplicated() = runTest {
        val ranker = ranker()

        val ranked = ranker.rank(listOf("zeta", "alpha", "zeta", "beta", "alpha"))

        assertEquals(listOf("alpha", "beta", "zeta"), ranked.map { it.recordingId })
    }

    private fun ranker(
        ratings: Map<String, ExplicitRecordingRatingSignal> = emptyMap(),
        behavior: Map<String, RecordingBehaviorSummary> = emptyMap(),
        recentEvents: Map<String, List<ListeningEvent>> = emptyMap(),
    ): PersonalRanker = PersonalRanker(
        behaviorReader = FakeBehaviorReader(behavior, recentEvents),
        ratingReader = FakeRatingReader(ratings),
        nowEpochMs = { NOW },
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

    private fun terminalEvent(
        recordingId: String,
        occurredAtEpochMs: Long,
        type: ListeningEventType,
        listenedDurationMs: Long,
    ) = ListeningEvent(
        id = "event-$recordingId-$occurredAtEpochMs",
        sessionId = "session",
        recordingId = recordingId,
        mediaAssetId = null,
        assetProvider = null,
        occurredAtEpochMs = occurredAtEpochMs,
        type = type,
        listenedDurationMs = listenedDurationMs,
        recordingDurationMs = 180_000,
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
