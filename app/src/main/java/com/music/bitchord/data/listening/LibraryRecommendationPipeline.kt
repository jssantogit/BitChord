package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import kotlinx.coroutines.CancellationException

/**
 * Read-only Library Mode candidate generation.
 *
 * Catalog owns eligibility. This source receives only canonical recordingId,
 * maps it to the ranker's existing candidate contract, and performs no scoring,
 * Listening reads, or persistence.
 */
internal class LibraryRecommendationCandidateSource(
    private val availabilityReader: CanonicalLibraryAvailabilityReader,
) {
    suspend fun candidates(): List<RecommendationCandidate> =
        availabilityReader.availableRecordingIds()
            .asSequence()
            .onEach { recordingId ->
                require(recordingId.isNotBlank()) { "Canonical recordingId must not be blank" }
            }
            .distinct()
            .sorted()
            .map(::RecommendationCandidate)
            .toList()
}

/**
 * Library Mode composition: availability -> candidates -> PersonalRanker -> session context.
 * Limit is deliberately applied only after both ranking stages.
 *
 * Observability is read-only and defaults to a no-op reporter. The reporter consumes the
 * rankers' existing explanations; it never participates in scoring or ordering.
 */
internal class LibraryRecommendationPipeline(
    private val candidateSource: LibraryRecommendationCandidateSource,
    private val personalRanker: PersonalRanker,
    private val sessionContextReranker: SessionContextReranker,
    private val traceReporter: RecommendationTraceReporter = RecommendationTraceReporter.NO_OP,
) {
    suspend fun rank(limit: Int? = null): List<SessionContextRankedCandidate> {
        require(limit == null || limit > 0) { "Recommendation limit must be positive" }
        val trace = traceReporter.startLibraryRank(limit)
        return try {
            val candidates = trace.span(RecommendationTraceStage.CANDIDATE_GENERATION) {
                candidateSource.candidates()
            }
            trace.stageResult(RecommendationTraceStage.CANDIDATE_GENERATION, candidates.size)

            val baseRanking = trace.span(
                stage = RecommendationTraceStage.PERSONAL_RANK,
                attributes = mapOf("inputCount" to candidates.size.toString()),
            ) {
                personalRanker.rank(candidates)
            }
            trace.stageResult(RecommendationTraceStage.PERSONAL_RANK, baseRanking.size)

            val contextualRanking = trace.span(
                stage = RecommendationTraceStage.SESSION_RERANK,
                attributes = mapOf("inputCount" to baseRanking.size.toString()),
            ) {
                sessionContextReranker.rerank(baseRanking)
            }
            trace.stageResult(RecommendationTraceStage.SESSION_RERANK, contextualRanking.size)

            val finalOutput = limit?.let(contextualRanking::take) ?: contextualRanking
            trace.complete(
                baseRanking = baseRanking,
                contextualRanking = contextualRanking,
                finalOutput = finalOutput,
            )
            finalOutput
        } catch (cancelled: CancellationException) {
            trace.cancel()
            throw cancelled
        } catch (error: Throwable) {
            trace.fail(error)
            throw error
        }
    }
}