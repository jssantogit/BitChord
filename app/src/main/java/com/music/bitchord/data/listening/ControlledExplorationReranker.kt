package com.music.bitchord.data.listening

/** Presentation-neutral reason; never stored in the Listening Event Store. */
internal enum class ExplorationReason {
    UNDEREXPOSED_PROMOTION,
}

/**
 * v0 policy allows conservative tuning without broadening the approved exploration ceiling.
 * Ranks are one-based; the underlying list indices remain zero-based.
 */
internal data class ControlledExplorationPolicy(
    val protectedTopPositions: Int = 3,
    val promotionPosition: Int = 5,
    val maximumTopPositions: Int = 10,
    val lookahead: Int = 20,
    val maximumScoreGap: Double = 10.0,
    val neutralRating: Double = 3.0,
) {
    init {
        require(protectedTopPositions >= 3)
        require(promotionPosition >= 5 && promotionPosition > protectedTopPositions)
        require(maximumTopPositions in promotionPosition..10)
        require(lookahead in 1..20)
        require(maximumScoreGap.isFinite() && maximumScoreGap in 0.0..10.0)
        require(neutralRating.isFinite() && neutralRating == 3.0)
    }
}

/**
 * A wrapper, not a recomputed score: all PersonalRanker, Session Context and Diversity
 * components and reasons stay available through [diversity], including their original values.
 * Compatibility projections expose the previously published pipeline read shape.
 */
internal data class ExplorationRankedCandidate(
    val diversity: DiversityRankedCandidate,
    val originalRank: Int,
    val explorationReasons: Set<ExplorationReason> = emptySet(),
    val promotedFromRank: Int? = null,
    val promotedToRank: Int? = null,
) {
    val base: SessionContextRankedCandidate get() = diversity.base
    val diversityAdjustment: Double get() = diversity.diversityAdjustment
    val finalScore: Double get() = diversity.finalScore
    val diversityReasons: Set<DiversityReason> get() = diversity.diversityReasons
    val overlappingArtistIds: Set<String> get() = diversity.overlappingArtistIds
}

/**
 * Read-only, deterministic and at-most-one promotion of a genuinely underexposed Recording.
 *
 * Only post-Diversity candidates are considered: the caller's Catalog availability gate
 * already selected canonical RecordingIds. There are no extra DB reads, side effects,
 * random seeds, synthetic Listening events or external candidate sources here.
 */
internal class ControlledExplorationReranker(
    private val policy: ControlledExplorationPolicy = ControlledExplorationPolicy(),
) {
    fun rerank(upstreamRanking: List<DiversityRankedCandidate>): List<ExplorationRankedCandidate> {
        if (upstreamRanking.isEmpty()) return emptyList()
        val ids = upstreamRanking.map { it.base.base.recordingId }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) {
            "Exploration requires unique canonical recording candidates"
        }
        require(upstreamRanking.all {
            it.finalScore.isFinite() && it.base.base.features.recordingId == it.base.base.recordingId
        }) { "Exploration requires finite scores and matching first-party Recording features" }

        val result = upstreamRanking.mapIndexed { index, candidate ->
            ExplorationRankedCandidate(diversity = candidate, originalRank = index + 1)
        }.toMutableList()

        // The approved target rank is five; never promote for fewer than five candidates.
        val targetIndex = policy.promotionPosition - 1
        if (result.size <= targetIndex + 1) return result

        val target = result[targetIndex].diversity
        val targetRating = target.base.base.features.explicitRating
        // Existing positive preference must not be displaced for exploration.
        if (targetRating != null && (targetRating > policy.neutralRating || !targetRating.isFinite())) {
            return result
        }
        // The desired slot is already providing controlled novelty; do not force another.
        if (isUnderexposed(target)) return result

        val endExclusive = minOf(result.size, targetIndex + 1 + policy.lookahead)
        val chosenIndex = (targetIndex + 1 until endExclusive)
            .filter { index ->
                val candidate = result[index].diversity
                isUnderexposed(candidate) &&
                    target.finalScore - candidate.finalScore <= policy.maximumScoreGap
            }
            .sortedWith(
                compareByDescending<Int> { result[it].finalScore }
                    .thenBy { it }
                    .thenBy { result[it].base.base.recordingId },
            )
            .firstOrNull()
            ?: return result

        val chosen = result.removeAt(chosenIndex)
        result.add(
            targetIndex,
            chosen.copy(
                explorationReasons = setOf(ExplorationReason.UNDEREXPOSED_PROMOTION),
                promotedFromRank = chosen.originalRank,
                promotedToRank = policy.promotionPosition,
            ),
        )
        check(result.size == upstreamRanking.size)
        return result
    }

    private fun isUnderexposed(candidate: DiversityRankedCandidate): Boolean {
        val features = candidate.base.base.features
        val rating = features.explicitRating
        if (rating != null && (!rating.isFinite() || rating != policy.neutralRating)) return false

        val behavior = features.behaviorSummary
        // A lone PLAYBACK_STARTED and interruptions are not completions or dislike.
        // Completion and repeated manual/replay choice are evidence of familiarity.
        if (behavior.completions > 0L || features.replays > 0 || features.manualSelections >= 2) {
            return false
        }
        // Predominant early skips are negative first-party outcomes, not discovery candidates.
        if (behavior.earlySkips > behavior.lateSkips + behavior.completions) return false
        return true
    }
}
