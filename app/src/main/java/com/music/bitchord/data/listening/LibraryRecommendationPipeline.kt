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
 * Library Mode composition: availability -> candidates -> existing PersonalRanker.
 * No second scoring algorithm or persistence is introduced here.
 */
internal class LibraryRecommendationPipeline(
    private val candidateSource: LibraryRecommendationCandidateSource,
    private val personalRanker: PersonalRanker,
) {
    suspend fun rank(limit: Int? = null): List<RankedRecordingCandidate> {
        require(limit == null || limit > 0) { "Recommendation limit must be positive" }
        val ranked = personalRanker.rank(candidateSource.candidates())
        return limit?.let(ranked::take) ?: ranked
    }
}
