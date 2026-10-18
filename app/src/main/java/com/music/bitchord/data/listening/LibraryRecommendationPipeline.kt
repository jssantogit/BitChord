package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader

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
 * Library Mode composition:
 * availability -> candidates -> PersonalRanker -> session context -> artist diversity -> controlled exploration.
 * Limit is deliberately applied only after every ranking stage.
 */
internal class LibraryRecommendationPipeline(
    private val candidateSource: LibraryRecommendationCandidateSource,
    private val personalRanker: PersonalRanker,
    private val sessionContextReranker: SessionContextReranker,
    private val artistAwareDiversityReranker: ArtistAwareDiversityReranker,
    private val controlledExplorationReranker: ControlledExplorationReranker = ControlledExplorationReranker(),
) {
    suspend fun rank(limit: Int? = null): List<ExplorationRankedCandidate> {
        require(limit == null || limit > 0) { "Recommendation limit must be positive" }
        val baseRanking = personalRanker.rank(candidateSource.candidates())
        val contextualRanking = sessionContextReranker.rerank(baseRanking)
        val diversifiedRanking = artistAwareDiversityReranker.rerank(contextualRanking)
        val exploredRanking = controlledExplorationReranker.rerank(diversifiedRanking)
        return limit?.let(exploredRanking::take) ?: exploredRanking
    }
}
