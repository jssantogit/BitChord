package com.music.bitchord.data.listening

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContextRerankerTest {

    @Test
    fun `no current session preserves base ranking with zero adjustment`() = runTest {
        val reader = FakeSessionContextReader(current = null)
        val base = listOf(ranked("recording-a", 60.0), ranked("recording-b", 48.0))

        val result = reranker(reader, now = NOW).rerank(base)

        assertEquals(base, result.map { it.base })
        assertEquals(listOf(0.0, 0.0), result.map { it.sessionAdjustment })
        assertEquals(base.map { it.score }, result.map { it.finalScore })
        assertTrue(result.all { it.sessionReasons.isEmpty() })
    }

    @Test
    fun `current session without candidate events is neutral`() = runTest {
        val reader = FakeSessionContextReader(current = session(), events = emptyList())

        val result = reranker(reader, NOW).rerank(listOf(ranked("recording-a", 25.0)))

        assertEquals(0.0, result.single().sessionAdjustment, EPSILON)
        assertEquals(25.0, result.single().finalScore, EPSILON)
    }

    @Test
    fun `playback started alone is neutral`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(event("start", "recording-a", ListeningEventType.PLAYBACK_STARTED, NOW, null)),
        )

        val result = reranker(reader, NOW).rerank(listOf(ranked("recording-a", 25.0))).single()

        assertEquals(0.0, result.sessionAdjustment, EPSILON)
        assertTrue(result.sessionReasons.isEmpty())
    }

    @Test
    fun `audible interruption is neutral and can replace an older skip`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(
                event("early", "recording-a", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW - 2_000L, 1_000L),
                event("interruption", "recording-a", ListeningEventType.PLAYBACK_INTERRUPTED, NOW - 1_000L, 1_000L),
            ),
        )

        val result = reranker(reader, NOW).rerank(listOf(ranked("recording-a", 25.0))).single()

        assertEquals(0.0, result.sessionAdjustment, EPSILON)
        assertEquals(25.0, result.finalScore, EPSILON)
        assertTrue(result.sessionReasons.isEmpty())
    }

    @Test
    fun `terminal outcome with zero listened duration is neutral`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(event("early", "recording-a", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW, 0L)),
        )

        val result = reranker(reader, NOW).rerank(listOf(ranked("recording-a", 25.0))).single()

        assertEquals(0.0, result.sessionAdjustment, EPSILON)
        assertTrue(result.sessionReasons.isEmpty())
    }

    @Test
    fun `fresh completion applies recently completed cooldown without mutating base`() = runTest {
        val base = ranked("recording-a", 60.0)
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(event("complete", base.recordingId, ListeningEventType.PLAYBACK_COMPLETED, NOW, 180_000L)),
        )

        val result = reranker(reader, NOW).rerank(listOf(base)).single()

        assertEquals(-8.0, result.sessionAdjustment, EPSILON)
        assertEquals(52.0, result.finalScore, EPSILON)
        assertEquals(setOf(SessionContextReason.SESSION_RECENTLY_COMPLETED), result.sessionReasons)
        assertEquals(base, result.base)
        assertEquals(60.0, result.base.components.total, EPSILON)
    }

    @Test
    fun `fresh early skip is stronger than fresh late skip with distinct reasons`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(
                event("early", "recording-early", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW, 5_000L),
                event("late", "recording-late", ListeningEventType.PLAYBACK_SKIPPED_LATE, NOW, 120_000L),
            ),
        )

        val result = reranker(reader, NOW).rerank(
            listOf(ranked("recording-early", 50.0), ranked("recording-late", 50.0)),
        ).associateBy { it.base.recordingId }

        assertEquals(-20.0, requireNotNull(result["recording-early"]).sessionAdjustment, EPSILON)
        assertEquals(-10.0, requireNotNull(result["recording-late"]).sessionAdjustment, EPSILON)
        assertEquals(setOf(SessionContextReason.SESSION_EARLY_SKIP), requireNotNull(result["recording-early"]).sessionReasons)
        assertEquals(setOf(SessionContextReason.SESSION_LATE_SKIP), requireNotNull(result["recording-late"]).sessionReasons)
        assertTrue(requireNotNull(result["recording-early"]).sessionAdjustment < requireNotNull(result["recording-late"]).sessionAdjustment)
    }

    @Test
    fun `latest audible terminal outcome wins instead of accumulating session history`() = runTest {
        val base = listOf(
            ranked("early-then-complete", 50.0),
            ranked("complete-then-early", 50.0),
            ranked("many-early", 50.0),
        )
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(
                event("a1", "early-then-complete", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW - 3_000L, 1_000L),
                event("a2", "early-then-complete", ListeningEventType.PLAYBACK_COMPLETED, NOW, 180_000L),
                event("b1", "complete-then-early", ListeningEventType.PLAYBACK_COMPLETED, NOW - 3_000L, 180_000L),
                event("b2", "complete-then-early", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW, 1_000L),
                event("c1", "many-early", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW - 2_000L, 1_000L),
                event("c2", "many-early", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW - 1_000L, 1_000L),
                event("c3", "many-early", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW, 1_000L),
            ),
        )

        val result = reranker(reader, NOW).rerank(base).associateBy { it.base.recordingId }

        assertEquals(-8.0, requireNotNull(result["early-then-complete"]).sessionAdjustment, EPSILON)
        assertEquals(-20.0, requireNotNull(result["complete-then-early"]).sessionAdjustment, EPSILON)
        assertEquals(-20.0, requireNotNull(result["many-early"]).sessionAdjustment, EPSILON)
    }

    @Test
    fun `context adjustment decays linearly and reaches zero at window`() = runTest {
        val policy = SessionContextPolicy(contextWindowMs = 2_000L)
        val event = event("complete", "recording-a", ListeningEventType.PLAYBACK_COMPLETED, 1_000L, 100_000L)
        val reader = FakeSessionContextReader(current = session(lastActivity = 3_000L), events = listOf(event))
        val base = listOf(ranked("recording-a", 50.0))

        val fresh = SessionContextReranker(reader, policy, nowEpochMs = { 1_000L }).rerank(base).single()
        val halfway = SessionContextReranker(reader, policy, nowEpochMs = { 2_000L }).rerank(base).single()
        val expired = SessionContextReranker(reader, policy, nowEpochMs = { 3_000L }).rerank(base).single()

        assertEquals(-8.0, fresh.sessionAdjustment, EPSILON)
        assertEquals(-4.0, halfway.sessionAdjustment, EPSILON)
        assertEquals(0.0, expired.sessionAdjustment, EPSILON)
        assertTrue(expired.sessionReasons.isEmpty())
    }

    @Test
    fun `future timestamp remains finite and bounded`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(lastActivity = NOW),
            events = listOf(event("early", "recording-a", ListeningEventType.PLAYBACK_SKIPPED_EARLY, Long.MAX_VALUE, 1_000L)),
        )

        val result = reranker(reader, NOW).rerank(listOf(ranked("recording-a", -95.0))).single()

        assertTrue(result.sessionAdjustment.isFinite())
        assertTrue(result.finalScore.isFinite())
        assertTrue(result.finalScore in -100.0..100.0)
        assertEquals(-100.0, result.finalScore, EPSILON)
    }

    @Test
    fun `invalid session policy is rejected deterministically`() {
        assertTrue(runCatching { SessionContextPolicy(contextWindowMs = 0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SessionContextPolicy(completionAdjustment = 1.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SessionContextPolicy(minimumScore = 1.0, maximumScore = 1.0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `session adjustment can change order and final ties preserve base rank information`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(
                event("a", "recording-a", ListeningEventType.PLAYBACK_SKIPPED_EARLY, NOW, 1_000L),
                event("b", "recording-b", ListeningEventType.PLAYBACK_COMPLETED, NOW, 180_000L),
            ),
        )
        val result = reranker(reader, NOW).rerank(
            listOf(
                ranked("recording-a", 60.0),
                ranked("recording-b", 48.0),
                ranked("recording-c", 45.0),
            ),
        )

        assertEquals(listOf("recording-c", "recording-a", "recording-b"), result.map { it.base.recordingId })
        assertEquals(listOf(45.0, 40.0, 40.0), result.map { it.finalScore })
        assertEquals(60.0, result[1].base.score, EPSILON)
        assertEquals(48.0, result[2].base.score, EPSILON)
    }

    @Test
    fun `final and base ties use recording id and repeated reranks are deterministic`() = runTest {
        val reader = FakeSessionContextReader(current = session(), events = emptyList())
        val base = listOf(ranked("recording-z", 10.0), ranked("recording-a", 10.0), ranked("recording-m", 10.0))
        val reranker = reranker(reader, NOW)

        val first = reranker.rerank(base)
        val second = reranker.rerank(base)

        assertEquals(listOf("recording-a", "recording-m", "recording-z"), first.map { it.base.recordingId })
        assertEquals(first, second)
    }

    @Test
    fun `current session and events are read at most once with no candidate N plus one`() = runTest {
        val reader = FakeSessionContextReader(
            current = session(),
            events = listOf(event("complete", "recording-0", ListeningEventType.PLAYBACK_COMPLETED, NOW, 1_000L)),
        )
        val candidates = (0 until 100).map { ranked("recording-$it", 10.0) }

        reranker(reader, NOW).rerank(candidates)

        assertEquals(1, reader.currentSessionReads)
        assertEquals(1, reader.eventsForSessionReads)
    }

    @Test
    fun `no session avoids events query entirely`() = runTest {
        val reader = FakeSessionContextReader(current = null)

        reranker(reader, NOW).rerank(listOf(ranked("recording-a", 10.0)))

        assertEquals(1, reader.currentSessionReads)
        assertEquals(0, reader.eventsForSessionReads)
    }

    private fun reranker(reader: ListeningSessionContextReader, now: Long): SessionContextReranker =
        SessionContextReranker(reader, nowEpochMs = { now })

    private fun session(lastActivity: Long = NOW): ListeningSession = ListeningSession(
        id = "session-current",
        startedAtEpochMs = 0L,
        lastActivityAtEpochMs = lastActivity,
        endedAtEpochMs = null,
    )

    private fun event(
        id: String,
        recordingId: String,
        type: ListeningEventType,
        occurredAt: Long,
        listenedDurationMs: Long?,
    ): ListeningEvent = ListeningEvent(
        id = id,
        sessionId = "session-current",
        recordingId = recordingId,
        mediaAssetId = null,
        assetProvider = null,
        occurredAtEpochMs = occurredAt,
        type = type,
        listenedDurationMs = listenedDurationMs,
        recordingDurationMs = 180_000L,
        endCause = null,
        selectionIntent = null,
        selectionOrigin = null,
        recommendationOrigin = null,
    )

    private fun ranked(recordingId: String, score: Double): RankedRecordingCandidate = RankedRecordingCandidate(
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
            explicitRating = null,
            explicitRatedAtEpochMs = null,
            lastRatingChangedAtEpochMs = null,
            lastRatingChangeDirection = null,
            behaviorSummary = RecordingBehaviorSummary(
                recordingId = recordingId,
                starts = 0L,
                completions = 0L,
                earlySkips = 0L,
                lateSkips = 0L,
                interruptions = 0L,
                totalListenedMs = 0L,
                lastEventAtEpochMs = null,
            ),
            manualSelections = 0,
            replays = 0,
            lastAudibleListenAtEpochMs = null,
        ),
    )

    private class FakeSessionContextReader(
        private val current: ListeningSession?,
        private val events: List<ListeningEvent> = emptyList(),
    ) : ListeningSessionContextReader {
        var currentSessionReads = 0
            private set
        var eventsForSessionReads = 0
            private set

        override suspend fun currentSession(atEpochMs: Long): ListeningSession? {
            currentSessionReads += 1
            return current
        }

        override suspend fun eventsForSession(sessionId: String): List<ListeningEvent> {
            eventsForSessionReads += 1
            return events
        }
    }

    private companion object {
        const val NOW = 1_000_000L
        const val EPSILON = 0.000001
    }
}
