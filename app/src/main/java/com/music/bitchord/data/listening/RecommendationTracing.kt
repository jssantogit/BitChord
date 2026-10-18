package com.music.bitchord.data.listening

import com.music.bitchord.observability.StructuredTrace
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceMode
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TraceVerbosity
import kotlin.math.abs

/** Fixed stage taxonomy for the current Library Mode recommendation pipeline. */
internal enum class RecommendationTraceStage(val key: String) {
    CANDIDATE_GENERATION("candidate_generation"),
    PERSONAL_RANK("personal_rank"),
    SESSION_RERANK("session_rerank"),
    ARTIST_DIVERSITY("artist_diversity"),
    CONTROLLED_EXPLORATION("controlled_exploration"),
}

/** Bounded detail policy. These limits are diagnostics policy, never ranking policy. */
internal data class RecommendationTracePolicy(
    val standardTopResults: Int = 10,
    val standardLargestMovers: Int = 10,
    val verboseMaxCandidates: Int = 40,
) {
    init {
        require(standardTopResults >= 0)
        require(standardLargestMovers >= 0)
        require(standardTopResults + standardLargestMovers > 0)
        require(verboseMaxCandidates >= standardTopResults + standardLargestMovers)
    }
}

/** Recommendation-owned seam. Ranking code never depends on Android Diagnostics. */
internal interface RecommendationTraceReporter {
    fun startLibraryRank(limit: Int?): RecommendationRunTrace

    companion object {
        val NO_OP: RecommendationTraceReporter = object : RecommendationTraceReporter {
            override fun startLibraryRank(limit: Int?): RecommendationRunTrace = NoOpRecommendationRunTrace
        }
    }
}

internal interface RecommendationRunTrace {
    suspend fun <T> span(
        stage: RecommendationTraceStage,
        attributes: Map<String, String> = emptyMap(),
        block: suspend () -> T,
    ): T

    fun stageResult(stage: RecommendationTraceStage, outputCount: Int)

    fun complete(
        baseRanking: List<RankedRecordingCandidate>,
        contextualRanking: List<SessionContextRankedCandidate>,
        diversifiedRanking: List<DiversityRankedCandidate>,
        exploredRanking: List<ExplorationRankedCandidate>,
        finalOutput: List<ExplorationRankedCandidate>,
    )

    fun fail(error: Throwable)
    fun cancel()
}

private object NoOpRecommendationRunTrace : RecommendationRunTrace {
    override suspend fun <T> span(
        stage: RecommendationTraceStage,
        attributes: Map<String, String>,
        block: suspend () -> T,
    ): T = block()

    override fun stageResult(stage: RecommendationTraceStage, outputCount: Int) = Unit

    override fun complete(
        baseRanking: List<RankedRecordingCandidate>,
        contextualRanking: List<SessionContextRankedCandidate>,
        diversifiedRanking: List<DiversityRankedCandidate>,
        exploredRanking: List<ExplorationRankedCandidate>,
        finalOutput: List<ExplorationRankedCandidate>,
    ) = Unit

    override fun fail(error: Throwable) = Unit
    override fun cancel() = Unit
}

internal class StructuredRecommendationTraceReporter(
    private val tracer: StructuredTracer,
    private val policy: RecommendationTracePolicy = RecommendationTracePolicy(),
) : RecommendationTraceReporter {
    override fun startLibraryRank(limit: Int?): RecommendationRunTrace {
        val trace = tracer.startTrace(
            feature = TraceFeature.RECOMMENDATION,
            operation = "library_rank",
            attributes = mapOf(
                "mode" to "library",
                "requestedLimit" to (limit?.toString() ?: "all"),
            ),
        )
        return StructuredRecommendationRunTrace(trace, tracer, policy, limit)
    }
}

private class StructuredRecommendationRunTrace(
    private val trace: StructuredTrace,
    private val tracer: StructuredTracer,
    private val policy: RecommendationTracePolicy,
    private val requestedLimit: Int?,
) : RecommendationRunTrace {
    override suspend fun <T> span(
        stage: RecommendationTraceStage,
        attributes: Map<String, String>,
        block: suspend () -> T,
    ): T = trace.spanSuspend(stage.key, attributes = attributes, block = block)

    override fun stageResult(stage: RecommendationTraceStage, outputCount: Int) {
        trace.event(
            stage = stage.key,
            outcome = TraceOutcome.SUCCEEDED,
            attributes = mapOf("outputCount" to outputCount.toString()),
        )
    }

    override fun complete(
        baseRanking: List<RankedRecordingCandidate>,
        contextualRanking: List<SessionContextRankedCandidate>,
        diversifiedRanking: List<DiversityRankedCandidate>,
        exploredRanking: List<ExplorationRankedCandidate>,
        finalOutput: List<ExplorationRankedCandidate>,
    ) {
        val baseRanks = baseRanking.mapIndexed { index, candidate -> candidate.recordingId to index + 1 }.toMap()
        val contextualRanks = contextualRanking
            .mapIndexed { index, candidate -> candidate.base.recordingId to index + 1 }
            .toMap()
        val contextualById = contextualRanking.associateBy { it.base.recordingId }
        val diversifiedById = diversifiedRanking.associateBy { it.base.base.recordingId }
        val diversityRanks = diversifiedRanking
            .mapIndexed { index, candidate -> candidate.base.base.recordingId to index + 1 }
            .toMap()
        val outputIds = finalOutput.mapTo(LinkedHashSet()) { it.diversity.base.base.recordingId }

        val topIds = finalOutput
            .take(policy.standardTopResults)
            .mapTo(LinkedHashSet()) { it.diversity.base.base.recordingId }
        val moverIds = contextualRanking
            .mapNotNull { candidate ->
                val id = candidate.base.recordingId
                val baseRank = baseRanks[id] ?: return@mapNotNull null
                val contextualRank = contextualRanks[id] ?: return@mapNotNull null
                val delta = baseRank - contextualRank
                if (delta == 0) null else CandidateMovement(id, delta, contextualRank)
            }
            .sortedWith(
                compareByDescending<CandidateMovement> { abs(it.rankDelta) }
                    .thenBy { it.contextualRank }
                    .thenBy { it.recordingId },
            )
            .take(policy.standardLargestMovers)
            .mapTo(LinkedHashSet()) { it.recordingId }

        val standardIds = LinkedHashSet<String>().apply {
            addAll(topIds)
            addAll(moverIds)
        }
        val selectedIds = LinkedHashSet<String>().apply {
            addAll(standardIds)
            if (tracer.mode == TraceMode.VERBOSE) {
                contextualRanking.forEach { candidate ->
                    if (size >= policy.verboseMaxCandidates) return@forEach
                    add(candidate.base.recordingId)
                }
            }
        }

        selectedIds.take(policy.verboseMaxCandidates).forEach { id ->
            val contextual = contextualById[id] ?: return@forEach
            val baseRank = baseRanks[id] ?: return@forEach
            val contextualRank = contextualRanks[id] ?: return@forEach
            val inTop = id in topIds
            val inMovers = id in moverIds
            val verbosity = if (id in standardIds) TraceVerbosity.STANDARD else TraceVerbosity.VERBOSE
            trace.event(
                stage = "candidate_explanation",
                outcome = TraceOutcome.SUCCEEDED,
                verbosity = verbosity,
                attributes = candidateAttributes(
                    contextual = contextual,
                    baseRank = baseRank,
                    contextualRank = contextualRank,
                    diversified = diversifiedById[id],
                    diversityRank = diversityRanks[id],
                    inFinalOutput = id in outputIds,
                    selection = when {
                        inTop && inMovers -> "top_and_mover"
                        inTop -> "top"
                        inMovers -> "mover"
                        else -> "verbose"
                    },
                ),
            )
        }

        // Exploration is a deterministic wrapper and may promote at most one candidate.
        // Preserve the full upstream reasons without exceeding the 24-attribute detail bound.
        exploredRanking.filter { it.explorationReasons.isNotEmpty() }.take(1).forEach { candidate ->
            trace.event(
                stage = "exploration_promotion",
                outcome = TraceOutcome.SUCCEEDED,
                attributes = mapOf(
                    "candidateRef" to trace.reference(candidate.diversity.base.base.recordingId),
                    "fromRank" to (candidate.promotedFromRank?.toString() ?: "unavailable"),
                    "toRank" to (candidate.promotedToRank?.toString() ?: "unavailable"),
                    "reason" to candidate.explorationReasons.map(Enum<*>::name).sorted().joinToString(","),
                ),
            )
        }

        trace.event(
            stage = "final_selection",
            outcome = TraceOutcome.SUCCEEDED,
            attributes = mapOf(
                "candidateCount" to baseRanking.size.toString(),
                "contextualCount" to contextualRanking.size.toString(),
                "diversifiedCount" to diversifiedRanking.size.toString(),
                "exploredCount" to exploredRanking.size.toString(),
                "outputCount" to finalOutput.size.toString(),
                "requestedLimit" to (requestedLimit?.toString() ?: "all"),
                "detailCount" to selectedIds.take(policy.verboseMaxCandidates).size.toString(),
            ),
        )
        trace.complete(
            TraceOutcome.SUCCEEDED,
            attributes = mapOf(
                "candidateCount" to baseRanking.size.toString(),
                "outputCount" to finalOutput.size.toString(),
            ),
        )
    }

    override fun fail(error: Throwable) {
        trace.complete(
            TraceOutcome.FAILED,
            attributes = mapOf("errorType" to error.javaClass.simpleName),
        )
    }

    override fun cancel() {
        trace.complete(TraceOutcome.CANCELLED)
    }

    private fun candidateAttributes(
        contextual: SessionContextRankedCandidate,
        baseRank: Int,
        contextualRank: Int,
        diversified: DiversityRankedCandidate?,
        diversityRank: Int?,
        inFinalOutput: Boolean,
        selection: String,
    ): Map<String, String> {
        val base = contextual.base
        val components = base.components
        return linkedMapOf(
            "candidateRef" to trace.reference(base.recordingId),
            "selection" to selection,
            "inFinalOutput" to inFinalOutput.toString(),
            "baseRank" to baseRank.toString(),
            "contextualRank" to contextualRank.toString(),
            "diversityRank" to (diversityRank?.toString() ?: "unavailable"),
            "diversityAdjustment" to (diversified?.diversityAdjustment?.toString() ?: "unavailable"),
            "diversityReasons" to (diversified?.diversityReasons?.map(Enum<*>::name)?.sorted()?.joinToString(",") ?: ""),
            "rankDelta" to (baseRank - contextualRank).toString(),
            "baseScore" to base.score.toString(),
            "sessionAdjustment" to contextual.sessionAdjustment.toString(),
            "finalScore" to contextual.finalScore.toString(),
            "explicitPreference" to components.explicitPreference.toString(),
            "completionEvidence" to components.completionEvidence.toString(),
            "earlySkipPenalty" to components.earlySkipPenalty.toString(),
            "lateSkipPenalty" to components.lateSkipPenalty.toString(),
            "manualSelectionEvidence" to components.manualSelectionEvidence.toString(),
            "replayEvidence" to components.replayEvidence.toString(),
            "behavioralEvidence" to components.behavioralEvidence.toString(),
            "recentListeningCooldown" to components.recentListeningCooldown.toString(),
            "recentRatingChange" to components.recentRatingChange.toString(),
            "componentTotal" to components.total.toString(),
            "personalReasons" to base.reasons.map(Enum<*>::name).sorted().joinToString(","),
            "sessionReasons" to contextual.sessionReasons.map(Enum<*>::name).sorted().joinToString(","),
        )
    }

    private data class CandidateMovement(
        val recordingId: String,
        val rankDelta: Int,
        val contextualRank: Int,
    )
}
