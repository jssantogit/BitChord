package com.music.bitchord.data.listening

/** Why a positively known library Recording may be worth rediscovering. */
internal enum class RediscoveryReason {
    POSITIVE_EXPLICIT_PREFERENCE,
    CONFIRMED_POSITIVE_BEHAVIOR,
    LONG_ABSENCE,
}

/** Tuning only. No new persistence, Catalog identity contract, or ranking stage. */
internal data class LibraryRediscoveryPreviewPolicy(
    val maxResults: Int = 5,
    val minimumAbsenceMs: Long = 30L * 24L * 60L * 60L * 1000L,
    val recentInteractionWindowMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val minimumPositiveRating: Double = 4.0,
    val negativeRatingThreshold: Double = 3.0,
) {
    init {
        require(maxResults in 1..10) { "Rediscovery preview size must be between 1 and 10" }
        require(minimumAbsenceMs > 0L && recentInteractionWindowMs > 0L) {
            "Rediscovery time windows must be positive"
        }
        require(minimumPositiveRating.isFinite() && negativeRatingThreshold.isFinite())
        require(minimumPositiveRating > negativeRatingThreshold)
    }
}

/**
 * Output carries the exact Exploration result from the existing complete Library ranking.
 * [rankPosition] is its one-based position in the actual input list (after Exploration).
 * No scores, upstream reasons, or canonical identities are changed or recalculated.
 */
internal data class RediscoveryPreviewCandidate(
    val source: ExplorationRankedCandidate,
    val rankPosition: Int,
    val lastAudibleListenAtEpochMs: Long,
    val lastInteractionAtEpochMs: Long?,
    val timeSinceLastAudibleMs: Long,
    val reasons: Set<RediscoveryReason>,
) {
    val recordingId: String get() = source.base.base.recordingId
    val finalScore: Double get() = source.finalScore
}

/**
 * Pure Library Mode preview planner. The caller must supply the *complete* result of
 * LibraryRecommendationPipeline.rank() without a limit. It only inspects already computed
 * first-party ranker features and never calls readers, changes the pipeline, writes data,
 * or promotes anything in the main recommendation order.
 *
 * lastAudibleListenAtEpochMs was derived from bounded real audible terminal events by
 * PersonalRanker. A missing timestamp is insufficient history, NOT proof of never listened.
 */
internal class LibraryRediscoveryPreviewPlanner(
    private val policy: LibraryRediscoveryPreviewPolicy = LibraryRediscoveryPreviewPolicy(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun preview(fullLibraryRanking: List<ExplorationRankedCandidate>): List<RediscoveryPreviewCandidate> {
        if (fullLibraryRanking.isEmpty()) return emptyList()
        val ids = fullLibraryRanking.map { it.base.base.recordingId }
        require(ids.all { it.isNotBlank() } && ids.size == ids.distinct().size) {
            "Rediscovery requires unique canonical RecordingIds"
        }
        require(fullLibraryRanking.all { candidate ->
            candidate.finalScore.isFinite() &&
                candidate.base.base.features.recordingId == candidate.base.base.recordingId &&
                candidate.base.base.features.behaviorSummary.recordingId == candidate.base.base.recordingId
        }) { "Rediscovery requires finite downstream scores and consistent first-party Recording features" }

        // One injected instant for every candidate: no clock drift across the shortlist.
        val now = nowEpochMs()
        if (now < 0L) return emptyList()

        return fullLibraryRanking.mapIndexedNotNull { index, source ->
            evaluate(source, rankPosition = index + 1, nowEpochMs = now)
        }.sortedWith(
            compareByDescending<RediscoveryPreviewCandidate> { it.timeSinceLastAudibleMs }
                .thenByDescending { it.finalScore }
                .thenBy { it.rankPosition }
                .thenBy { it.recordingId },
        ).take(policy.maxResults)
    }

    private fun evaluate(
        source: ExplorationRankedCandidate,
        rankPosition: Int,
        nowEpochMs: Long,
    ): RediscoveryPreviewCandidate? {
        val features = source.base.base.features
        val behavior = features.behaviorSummary

        // Never invent terminal listening from PLAYBACK_STARTED, unknown history or invalid clocks.
        val lastAudibleAt = features.lastAudibleListenAtEpochMs ?: return null
        val absentForMs = safeAge(nowEpochMs, lastAudibleAt) ?: return null
        if (absentForMs < policy.minimumAbsenceMs) return null

        val lastInteractionAt = behavior.lastEventAtEpochMs
        if (lastInteractionAt != null) {
            val interactionAge = safeAge(nowEpochMs, lastInteractionAt) ?: return null
            if (interactionAge < policy.recentInteractionWindowMs) return null
        }

        if (behavior.starts < 0L || behavior.completions < 0L ||
            behavior.earlySkips < 0L || behavior.lateSkips < 0L ||
            features.manualSelections < 0 || features.replays < 0
        ) return null

        val rating = features.explicitRating
        if (rating != null && rating.isFinite() && rating < policy.negativeRatingThreshold) return null
        // Compare without summing arbitrary Long counts (avoid overflow).
        if (behavior.earlySkips > behavior.completions &&
            behavior.earlySkips - behavior.completions > behavior.lateSkips
        ) return null
        if (behavior.completions < 1L) return null

        val explicitPositive = rating != null && rating.isFinite() && rating >= policy.minimumPositiveRating
        val behavioralPositive = behavior.completions >= 2L &&
            (features.manualSelections >= 1 || features.replays >= 1)
        if (!explicitPositive && !behavioralPositive) return null

        val reasons = linkedSetOf<RediscoveryReason>()
        if (explicitPositive) reasons += RediscoveryReason.POSITIVE_EXPLICIT_PREFERENCE
        if (behavioralPositive) reasons += RediscoveryReason.CONFIRMED_POSITIVE_BEHAVIOR
        reasons += RediscoveryReason.LONG_ABSENCE
        return RediscoveryPreviewCandidate(
            source = source,
            rankPosition = rankPosition,
            lastAudibleListenAtEpochMs = lastAudibleAt,
            lastInteractionAtEpochMs = lastInteractionAt,
            timeSinceLastAudibleMs = absentForMs,
            reasons = reasons,
        )
    }

    private fun safeAge(now: Long, past: Long): Long? {
        if (past < 0L || past > now) return null
        return try {
            Math.subtractExact(now, past)
        } catch (_: ArithmeticException) {
            null
        }
    }
}
