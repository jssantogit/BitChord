package com.music.bitchord.data.listening

/** Typed, presentation-neutral explanations for ephemeral current-session adjustments. */
internal enum class SessionContextReason {
    SESSION_RECENTLY_COMPLETED,
    SESSION_EARLY_SKIP,
    SESSION_LATE_SKIP,
}

/** v0 tuning policy. Values are runtime ranking policy, never persisted state/schema. */
internal data class SessionContextPolicy(
    val minimumScore: Double = -100.0,
    val maximumScore: Double = 100.0,
    val completionAdjustment: Double = -8.0,
    val earlySkipAdjustment: Double = -20.0,
    val lateSkipAdjustment: Double = -10.0,
    val contextWindowMs: Long = 2L * 60L * 60L * 1000L,
) {
    init {
        require(minimumScore.isFinite() && maximumScore.isFinite() && minimumScore < maximumScore)
        require(completionAdjustment.isFinite() && completionAdjustment <= 0.0)
        require(earlySkipAdjustment.isFinite() && earlySkipAdjustment <= 0.0)
        require(lateSkipAdjustment.isFinite() && lateSkipAdjustment <= 0.0)
        require(contextWindowMs > 0L)
    }
}

/** Keeps long-term PersonalRanker output and session context independently inspectable. */
internal data class SessionContextRankedCandidate(
    val base: RankedRecordingCandidate,
    val sessionAdjustment: Double,
    val finalScore: Double,
    val sessionReasons: Set<SessionContextReason>,
)

/**
 * Ephemeral current-session reranking.
 *
 * One current-session read and, when current, one timeline read feed an in-memory
 * projection keyed only by canonical recordingId. No score or feature is persisted.
 */
internal class SessionContextReranker(
    private val contextReader: ListeningSessionContextReader,
    private val policy: SessionContextPolicy = SessionContextPolicy(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun rerank(baseRanking: List<RankedRecordingCandidate>): List<SessionContextRankedCandidate> {
        val now = nowEpochMs()
        val currentSession = contextReader.currentSession(now)
        val latestOutcomes = if (currentSession == null) {
            emptyMap()
        } else {
            latestAudibleTerminalOutcomes(contextReader.eventsForSession(currentSession.id))
        }

        return baseRanking
            .map { base -> contextualize(base, latestOutcomes[base.recordingId], now) }
            .sortedWith(
                compareByDescending<SessionContextRankedCandidate> { it.finalScore }
                    .thenByDescending { it.base.score }
                    .thenBy { it.base.recordingId },
            )
    }

    private fun contextualize(
        base: RankedRecordingCandidate,
        outcome: ListeningEvent?,
        nowEpochMs: Long,
    ): SessionContextRankedCandidate {
        require(base.score.isFinite()) { "PersonalRanker score must be finite" }

        val signal = outcome?.let { sessionSignal(it, nowEpochMs) } ?: SessionSignal.NONE
        val adjustment = signal.adjustment.also {
            check(it.isFinite()) { "Session adjustment must be finite" }
        }
        val rawFinal = base.score + adjustment
        val finalScore = when {
            rawFinal.isFinite() -> rawFinal.coerceIn(policy.minimumScore, policy.maximumScore)
            rawFinal < 0.0 -> policy.minimumScore
            else -> policy.maximumScore
        }

        return SessionContextRankedCandidate(
            base = base,
            sessionAdjustment = adjustment,
            finalScore = finalScore,
            sessionReasons = signal.reasons,
        )
    }

    private fun sessionSignal(event: ListeningEvent, nowEpochMs: Long): SessionSignal {
        val (baseAdjustment, reason) = when (event.type) {
            ListeningEventType.PLAYBACK_COMPLETED ->
                policy.completionAdjustment to SessionContextReason.SESSION_RECENTLY_COMPLETED
            ListeningEventType.PLAYBACK_SKIPPED_EARLY ->
                policy.earlySkipAdjustment to SessionContextReason.SESSION_EARLY_SKIP
            ListeningEventType.PLAYBACK_SKIPPED_LATE ->
                policy.lateSkipAdjustment to SessionContextReason.SESSION_LATE_SKIP
            ListeningEventType.PLAYBACK_INTERRUPTED,
            ListeningEventType.PLAYBACK_STARTED -> return SessionSignal.NONE
        }

        val ageMs = safeAgeMs(nowEpochMs, event.occurredAtEpochMs)
        if (ageMs >= policy.contextWindowMs) return SessionSignal.NONE

        val remainingFraction = 1.0 - (ageMs.toDouble() / policy.contextWindowMs.toDouble())
        val adjustment = (baseAdjustment * remainingFraction)
            .coerceIn(baseAdjustment, 0.0)
        if (adjustment == 0.0) return SessionSignal.NONE

        return SessionSignal(adjustment, setOf(reason))
    }

    private fun latestAudibleTerminalOutcomes(events: List<ListeningEvent>): Map<String, ListeningEvent> {
        val latest = LinkedHashMap<String, ListeningEvent>()
        for (event in events) {
            if (!event.isAudibleTerminalOutcome()) continue
            val previous = latest[event.recordingId]
            if (previous == null || EVENT_ORDER.compare(event, previous) > 0) {
                latest[event.recordingId] = event
            }
        }
        return latest
    }

    private fun ListeningEvent.isAudibleTerminalOutcome(): Boolean {
        if ((listenedDurationMs ?: 0L) <= 0L) return false
        return type == ListeningEventType.PLAYBACK_COMPLETED ||
            type == ListeningEventType.PLAYBACK_SKIPPED_EARLY ||
            type == ListeningEventType.PLAYBACK_SKIPPED_LATE ||
            type == ListeningEventType.PLAYBACK_INTERRUPTED
    }

    private fun safeAgeMs(nowEpochMs: Long, eventEpochMs: Long): Long {
        if (eventEpochMs > nowEpochMs) return 0L
        return try {
            Math.subtractExact(nowEpochMs, eventEpochMs)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private data class SessionSignal(
        val adjustment: Double,
        val reasons: Set<SessionContextReason>,
    ) {
        companion object {
            val NONE = SessionSignal(0.0, emptySet())
        }
    }

    private companion object {
        val EVENT_ORDER: Comparator<ListeningEvent> =
            compareBy<ListeningEvent> { it.occurredAtEpochMs }.thenBy { it.id }
    }
}
