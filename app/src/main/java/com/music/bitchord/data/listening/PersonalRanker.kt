package com.music.bitchord.data.listening

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Caller-supplied canonical Recording candidate. Candidate generation is intentionally external. */
internal data class RecommendationCandidate(
    val recordingId: String,
) {
    init {
        require(recordingId.isNotBlank()) { "Canonical recordingId must not be blank" }
    }
}

/** Typed explanations; presentation/localization belongs outside the ranker. */
internal enum class RecommendationReason {
    EXPLICIT_FAVORITE,
    STRONG_EXPLICIT_PREFERENCE,
    POSITIVE_EXPLICIT_PREFERENCE,
    NEGATIVE_EXPLICIT_PREFERENCE,
    COMPLETION_HEAVY,
    EARLY_SKIP_HEAVY,
    LATE_SKIP_EVIDENCE,
    MANUAL_SELECTION_SIGNAL,
    REPLAY_SIGNAL,
    RECENT_LISTEN_COOLDOWN,
    RECENT_RATING_SET,
    RECENT_RATING_INCREASE,
    RECENT_RATING_DECREASE,
}

/** Read-only feature snapshot used to explain one ranking decision. */
internal data class RecordingRecommendationFeatures(
    val recordingId: String,
    val explicitRating: Double?,
    val explicitRatedAtEpochMs: Long?,
    val lastRatingChangedAtEpochMs: Long?,
    val lastRatingChangeDirection: RecordingRatingDirection?,
    val behaviorSummary: RecordingBehaviorSummary,
    val manualSelections: Int,
    val replays: Int,
    val lastAudibleListenAtEpochMs: Long?,
)

/** Every component is inspectable; [behavioralEvidence] is the bounded behavioral subtotal. */
internal data class RecommendationScoreComponents(
    val explicitPreference: Double,
    val completionEvidence: Double,
    val earlySkipPenalty: Double,
    val lateSkipPenalty: Double,
    val manualSelectionEvidence: Double,
    val replayEvidence: Double,
    val behavioralEvidence: Double,
    val recentListeningCooldown: Double,
    val recentRatingChange: Double,
    val total: Double,
)

internal data class RankedRecordingCandidate(
    val recordingId: String,
    val score: Double,
    val components: RecommendationScoreComponents,
    val reasons: Set<RecommendationReason>,
    val features: RecordingRecommendationFeatures,
)

/**
 * Central v0 tuning policy. Values are deliberately policy, not persistence/schema contracts.
 * They can evolve without changing canonical identity or reader boundaries.
 */
internal data class PersonalRankerPolicy(
    val minimumScore: Double = -100.0,
    val maximumScore: Double = 100.0,
    val favoriteRating: Double = 5.0,
    val strongRating: Double = 4.5,
    val goodRating: Double = 4.0,
    val neutralRating: Double = 3.0,
    val favoriteExplicitScore: Double = 60.0,
    val strongExplicitScore: Double = 48.0,
    val goodExplicitScore: Double = 34.0,
    val neutralExplicitScore: Double = 0.0,
    val nearNeutralNegativeScore: Double = -18.0,
    val minimumExplicitScore: Double = -55.0,
    val completionWeight: Double = 4.0,
    val maximumCompletionBoost: Double = 16.0,
    val earlySkipWeight: Double = 7.0,
    val maximumEarlySkipPenalty: Double = 24.0,
    val lateSkipWeight: Double = 3.0,
    val maximumLateSkipPenalty: Double = 12.0,
    val manualSelectionWeight: Double = 2.0,
    val maxManualSelectionBoost: Double = 8.0,
    val replayWeight: Double = 3.0,
    val maxReplayBoost: Double = 9.0,
    val minimumBehavioralEvidence: Double = -24.0,
    val maximumBehavioralEvidence: Double = 18.0,
    val cooldownWindowMs: Long = 24L * 60L * 60L * 1000L,
    val maximumCooldownPenalty: Double = 12.0,
    val fourPointFiveCooldownMultiplier: Double = 1.25,
    val ratingChangeWindowMs: Long = 14L * 24L * 60L * 60L * 1000L,
    val maxRecentRatingChangeAdjustment: Double = 4.0,
    val recentInitialSetAdjustment: Double = 2.0,
    val recentEventLimit: Int = 50,
) {
    init {
        require(minimumScore.isFinite() && maximumScore.isFinite() && minimumScore < maximumScore)
        require(neutralRating < goodRating && goodRating < strongRating && strongRating < favoriteRating)
        require(favoriteExplicitScore > strongExplicitScore)
        require(strongExplicitScore > goodExplicitScore)
        require(goodExplicitScore > neutralExplicitScore)
        require(nearNeutralNegativeScore < neutralExplicitScore)
        require(minimumExplicitScore <= nearNeutralNegativeScore)
        require(completionWeight >= 0.0 && maximumCompletionBoost >= 0.0)
        require(earlySkipWeight >= 0.0 && maximumEarlySkipPenalty >= 0.0)
        require(lateSkipWeight >= 0.0 && maximumLateSkipPenalty >= 0.0)
        require(manualSelectionWeight >= 0.0 && maxManualSelectionBoost >= 0.0)
        require(replayWeight >= 0.0 && maxReplayBoost >= 0.0)
        require(minimumBehavioralEvidence <= 0.0 && maximumBehavioralEvidence >= 0.0)
        require(cooldownWindowMs > 0L && maximumCooldownPenalty >= 0.0)
        require(fourPointFiveCooldownMultiplier >= 1.0)
        require(ratingChangeWindowMs > 0L && maxRecentRatingChangeAdjustment >= 0.0)
        require(abs(recentInitialSetAdjustment) <= maxRecentRatingChangeAdjustment)
        require(recentEventLimit > 0)
    }

    /** Non-linear explicit preference semantics. Any out-of-range finite value is safely bounded. */
    fun explicitPreference(rating: Double?): Double {
        if (rating == null || !rating.isFinite()) return 0.0
        val bounded = rating.coerceIn(0.0, favoriteRating)
        return when {
            bounded >= strongRating -> interpolate(
                bounded,
                strongRating,
                favoriteRating,
                strongExplicitScore,
                favoriteExplicitScore,
            )

            bounded >= goodRating -> interpolate(
                bounded,
                goodRating,
                strongRating,
                goodExplicitScore,
                strongExplicitScore,
            )

            bounded >= neutralRating -> interpolate(
                bounded,
                neutralRating,
                goodRating,
                neutralExplicitScore,
                goodExplicitScore,
            )

            else -> {
                val distanceFromNeutral = (neutralRating - bounded) / neutralRating
                nearNeutralNegativeScore +
                    distanceFromNeutral * (minimumExplicitScore - nearNeutralNegativeScore)
            }
        }.coerceIn(minimumExplicitScore, favoriteExplicitScore)
    }

    private fun interpolate(
        value: Double,
        lowerValue: Double,
        upperValue: Double,
        lowerScore: Double,
        upperScore: Double,
    ): Double {
        if (upperValue == lowerValue) return upperScore
        val fraction = ((value - lowerValue) / (upperValue - lowerValue)).coerceIn(0.0, 1.0)
        return lowerScore + fraction * (upperScore - lowerScore)
    }
}

/**
 * First deterministic personal ranker baseline.
 *
 * It is derived/read-only: it reads first-party Listening signals, never mutates playback,
 * queue, ratings, catalog or Event Store, and knows only canonical recordingId identity.
 */
internal class PersonalRanker(
    private val behaviorReader: ListeningBehaviorReader,
    private val ratingReader: ExplicitRecordingRatingReader,
    private val policy: PersonalRankerPolicy = PersonalRankerPolicy(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun rank(candidates: List<RecommendationCandidate>): List<RankedRecordingCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val now = nowEpochMs()
        val uniqueCandidates = candidates.distinctBy(RecommendationCandidate::recordingId)
        return uniqueCandidates
            .map { candidate -> rankOne(candidate, now) }
            .sortedWith(
                compareByDescending<RankedRecordingCandidate> { it.score }
                    .thenBy { it.recordingId },
            )
    }

    private suspend fun rankOne(
        candidate: RecommendationCandidate,
        now: Long,
    ): RankedRecordingCandidate {
        val recordingId = candidate.recordingId
        val ratingSignal = ratingReader.explicitRatingSignal(recordingId)
        val summary = behaviorReader.behaviorSummary(recordingId)
        val recentEvents = behaviorReader.recentEventsForRecording(
            recordingId = recordingId,
            limit = policy.recentEventLimit,
        )
        val features = features(recordingId, ratingSignal, summary, recentEvents)
        val reasons = linkedSetOf<RecommendationReason>()

        val explicitPreference = policy.explicitPreference(features.explicitRating)
        addExplicitReason(features.explicitRating, reasons)

        val completionEvidence = boundedPositive(
            summary.completions,
            policy.completionWeight,
            policy.maximumCompletionBoost,
        )
        val earlySkipPenalty = -boundedPositive(
            summary.earlySkips,
            policy.earlySkipWeight,
            policy.maximumEarlySkipPenalty,
        )
        val lateSkipPenalty = -boundedPositive(
            summary.lateSkips,
            policy.lateSkipWeight,
            policy.maximumLateSkipPenalty,
        )
        val manualSelectionEvidence = min(
            features.manualSelections.toDouble() * policy.manualSelectionWeight,
            policy.maxManualSelectionBoost,
        )
        val replayEvidence = min(
            features.replays.toDouble() * policy.replayWeight,
            policy.maxReplayBoost,
        )

        if (completionEvidence > 0.0 && summary.completions > summary.earlySkips) {
            reasons += RecommendationReason.COMPLETION_HEAVY
        }
        if (earlySkipPenalty < 0.0 && summary.earlySkips > summary.completions) {
            reasons += RecommendationReason.EARLY_SKIP_HEAVY
        }
        if (lateSkipPenalty < 0.0) reasons += RecommendationReason.LATE_SKIP_EVIDENCE
        if (manualSelectionEvidence > 0.0) reasons += RecommendationReason.MANUAL_SELECTION_SIGNAL
        if (replayEvidence > 0.0) reasons += RecommendationReason.REPLAY_SIGNAL

        val behavioralEvidence = (
            completionEvidence +
                earlySkipPenalty +
                lateSkipPenalty +
                manualSelectionEvidence +
                replayEvidence
            ).coerceIn(
                policy.minimumBehavioralEvidence,
                policy.maximumBehavioralEvidence,
            )

        val recentRatingChange = recentRatingChange(features, now, reasons)
        val preCooldownScore = explicitPreference + behavioralEvidence + recentRatingChange
        val recentListeningCooldown = recentListeningCooldown(
            features = features,
            now = now,
            explicitPreference = explicitPreference,
            scoreBeforeCooldown = preCooldownScore,
            reasons = reasons,
        )

        val total = finiteBounded(
            preCooldownScore + recentListeningCooldown,
            policy.minimumScore,
            policy.maximumScore,
        )
        val components = RecommendationScoreComponents(
            explicitPreference = finiteBounded(
                explicitPreference,
                policy.minimumExplicitScore,
                policy.favoriteExplicitScore,
            ),
            completionEvidence = finiteBounded(completionEvidence, 0.0, policy.maximumCompletionBoost),
            earlySkipPenalty = finiteBounded(earlySkipPenalty, -policy.maximumEarlySkipPenalty, 0.0),
            lateSkipPenalty = finiteBounded(lateSkipPenalty, -policy.maximumLateSkipPenalty, 0.0),
            manualSelectionEvidence = finiteBounded(
                manualSelectionEvidence,
                0.0,
                policy.maxManualSelectionBoost,
            ),
            replayEvidence = finiteBounded(replayEvidence, 0.0, policy.maxReplayBoost),
            behavioralEvidence = finiteBounded(
                behavioralEvidence,
                policy.minimumBehavioralEvidence,
                policy.maximumBehavioralEvidence,
            ),
            recentListeningCooldown = finiteBounded(
                recentListeningCooldown,
                -policy.maximumCooldownPenalty * policy.fourPointFiveCooldownMultiplier,
                0.0,
            ),
            recentRatingChange = finiteBounded(
                recentRatingChange,
                -policy.maxRecentRatingChangeAdjustment,
                policy.maxRecentRatingChangeAdjustment,
            ),
            total = total,
        )
        check(components.allFinite()) { "Personal Ranker produced a non-finite score component" }

        return RankedRecordingCandidate(
            recordingId = recordingId,
            score = total,
            components = components,
            reasons = reasons,
            features = features,
        )
    }

    private fun features(
        recordingId: String,
        ratingSignal: ExplicitRecordingRatingSignal,
        summary: RecordingBehaviorSummary,
        recentEvents: List<ListeningEvent>,
    ): RecordingRecommendationFeatures {
        val manualSelections = recentEvents.count { event ->
            event.type == ListeningEventType.PLAYBACK_STARTED &&
                event.selectionIntent == SelectionIntent.MANUAL
        }
        val replays = recentEvents.count { event ->
            event.type == ListeningEventType.PLAYBACK_STARTED &&
                event.selectionIntent == SelectionIntent.REPLAY
        }
        val lastAudibleListenAt = recentEvents.asSequence()
            .filter { event -> event.isAudibleTerminalEvidence() }
            .maxOfOrNull { event -> event.occurredAtEpochMs }

        return RecordingRecommendationFeatures(
            recordingId = recordingId,
            explicitRating = ratingSignal.currentRating,
            explicitRatedAtEpochMs = ratingSignal.currentRatedAtEpochMs,
            lastRatingChangedAtEpochMs = ratingSignal.lastChangedAtEpochMs,
            lastRatingChangeDirection = ratingSignal.lastChangeDirection,
            behaviorSummary = summary,
            manualSelections = manualSelections,
            replays = replays,
            lastAudibleListenAtEpochMs = lastAudibleListenAt,
        )
    }

    private fun addExplicitReason(
        rating: Double?,
        reasons: MutableSet<RecommendationReason>,
    ) {
        if (rating == null || !rating.isFinite()) return
        when {
            rating >= policy.favoriteRating -> reasons += RecommendationReason.EXPLICIT_FAVORITE
            rating >= policy.strongRating -> reasons += RecommendationReason.STRONG_EXPLICIT_PREFERENCE
            rating > policy.neutralRating -> reasons += RecommendationReason.POSITIVE_EXPLICIT_PREFERENCE
            rating < policy.neutralRating -> reasons += RecommendationReason.NEGATIVE_EXPLICIT_PREFERENCE
        }
    }

    private fun recentRatingChange(
        features: RecordingRecommendationFeatures,
        now: Long,
        reasons: MutableSet<RecommendationReason>,
    ): Double {
        val currentRating = features.explicitRating ?: return 0.0
        if (!currentRating.isFinite()) return 0.0
        val direction = features.lastRatingChangeDirection ?: return 0.0
        if (direction == RecordingRatingDirection.CLEARED || direction == RecordingRatingDirection.UNCHANGED) {
            return 0.0
        }
        val changedAt = when (direction) {
            RecordingRatingDirection.INITIAL_SET ->
                features.explicitRatedAtEpochMs ?: features.lastRatingChangedAtEpochMs
            RecordingRatingDirection.INCREASED,
            RecordingRatingDirection.DECREASED -> features.lastRatingChangedAtEpochMs
            RecordingRatingDirection.UNCHANGED,
            RecordingRatingDirection.CLEARED -> null
        } ?: return 0.0
        val recency = decayingRecency(now, changedAt, policy.ratingChangeWindowMs)
        if (recency <= 0.0) return 0.0

        val fullAdjustment = when (direction) {
            RecordingRatingDirection.INITIAL_SET -> policy.recentInitialSetAdjustment
            RecordingRatingDirection.INCREASED -> policy.maxRecentRatingChangeAdjustment
            RecordingRatingDirection.DECREASED -> -policy.maxRecentRatingChangeAdjustment
            RecordingRatingDirection.UNCHANGED,
            RecordingRatingDirection.CLEARED -> 0.0
        }
        val adjustment = (fullAdjustment * recency).coerceIn(
            -policy.maxRecentRatingChangeAdjustment,
            policy.maxRecentRatingChangeAdjustment,
        )
        when {
            adjustment > 0.0 && direction == RecordingRatingDirection.INITIAL_SET ->
                reasons += RecommendationReason.RECENT_RATING_SET
            adjustment > 0.0 -> reasons += RecommendationReason.RECENT_RATING_INCREASE
            adjustment < 0.0 -> reasons += RecommendationReason.RECENT_RATING_DECREASE
        }
        return adjustment
    }

    private fun recentListeningCooldown(
        features: RecordingRecommendationFeatures,
        now: Long,
        explicitPreference: Double,
        scoreBeforeCooldown: Double,
        reasons: MutableSet<RecommendationReason>,
    ): Double {
        val listenedAt = features.lastAudibleListenAtEpochMs ?: return 0.0
        val recency = decayingRecency(now, listenedAt, policy.cooldownWindowMs)
        if (recency <= 0.0) return 0.0

        val rating = features.explicitRating
        val multiplier = if (rating != null && abs(rating - policy.strongRating) < 0.000_001) {
            policy.fourPointFiveCooldownMultiplier
        } else {
            1.0
        }
        var magnitude = policy.maximumCooldownPenalty * multiplier * recency

        // Cooldown may defer positive preference, but must not be the operation that flips it negative.
        if (explicitPreference > 0.0 && scoreBeforeCooldown > 0.0) {
            magnitude = min(magnitude, scoreBeforeCooldown)
        }
        if (magnitude <= 0.0) return 0.0

        reasons += RecommendationReason.RECENT_LISTEN_COOLDOWN
        return -magnitude
    }

    private fun decayingRecency(now: Long, occurredAt: Long, windowMs: Long): Double {
        val ageMs = max(0.0, now.toDouble() - occurredAt.toDouble())
        if (ageMs >= windowMs.toDouble()) return 0.0
        return (1.0 - ageMs / windowMs.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun boundedPositive(count: Long, weight: Double, cap: Double): Double {
        if (count <= 0L || weight <= 0.0 || cap <= 0.0) return 0.0
        return min(count.toDouble() * weight, cap)
    }

    private fun finiteBounded(value: Double, minimum: Double, maximum: Double): Double =
        if (value.isFinite()) value.coerceIn(minimum, maximum) else 0.0

    private fun ListeningEvent.isAudibleTerminalEvidence(): Boolean =
        type != ListeningEventType.PLAYBACK_STARTED &&
            (listenedDurationMs ?: 0L) > 0L

    private fun RecommendationScoreComponents.allFinite(): Boolean = listOf(
        explicitPreference,
        completionEvidence,
        earlySkipPenalty,
        lateSkipPenalty,
        manualSelectionEvidence,
        replayEvidence,
        behavioralEvidence,
        recentListeningCooldown,
        recentRatingChange,
        total,
    ).all { value -> value.isFinite() }
}
