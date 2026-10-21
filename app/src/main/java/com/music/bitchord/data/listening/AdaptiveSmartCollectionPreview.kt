package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader

/** Collection categories are read-only views, not playlist entities or new rank stages. */
internal enum class AdaptiveCollectionKind {
    REDISCOVERY,
    EXPLICIT_POSITIVES,
    CONTROLLED_EXPLORATION,
}

internal enum class AdaptiveCollectionReason {
    CONFIRMED_AUDIBLE_ABSENCE,
    POSITIVE_EXPLICIT_RATING,
    EXPLORATION_PROMOTION,
}

internal data class AdaptiveCollectionItem(
    val recordingId: String,
    val rankPosition: Int,
    val finalScore: Double,
    val reasons: Set<AdaptiveCollectionReason>,
)

internal data class AdaptiveCollectionPreview(
    val kind: AdaptiveCollectionKind,
    val items: List<AdaptiveCollectionItem>,
)

/**
 * L7 reuses exactly the existing complete Library recommendation pipeline:
 * PersonalRanker -> SessionContext -> ArtistDiversity -> ControlledExploration.
 * The planner never substitutes ranking, changes a score or infers a completion
 * from a start. Only #43 may classify a positive, long-absent Recording as
 * forgotten; controlled exploration is explicitly NOT such a classification.
 */
internal class AdaptiveSmartCollectionPreview(
    private val pipeline: LibraryRecommendationPipeline,
    private val availability: CanonicalLibraryAvailabilityReader,
    private val rediscovery: LibraryRediscoveryPreviewPlanner = LibraryRediscoveryPreviewPlanner(),
    private val maxPerCollection: Int = 5,
) {
    init { require(maxPerCollection in 1..10) }

    suspend fun preview(): List<AdaptiveCollectionPreview> {
        val full = pipeline.rank() // Intentionally NO limit before any ranking stage.
        val ids = full.map { it.base.base.recordingId }
        require(ids.none(String::isBlank) && ids.distinct().size == ids.size) {
            "Adaptive collections require unique canonical Recording IDs"
        }
        require(full.all { it.finalScore.isFinite() &&
            it.base.base.features.recordingId == it.base.base.recordingId }) {
            "Adaptive collection must preserve finite canonical ranking snapshots"
        }

        // Availability is rechecked after ranking. The next UI click still must
        // revalidate because this is a snapshot, never a navigability guarantee.
        val available = availability.availableRecordingIds().toHashSet()
        val positions = ids.withIndex().associate { (index, id) -> id to index + 1 }
        val ranked = full.filter { it.base.base.recordingId in available }
        fun acceptable(explicitRating: Double?): Boolean =
            explicitRating == null || (explicitRating.isFinite() && explicitRating >= 3.0)

        val rediscovered = rediscovery.preview(full)
            .asSequence()
            .filter { it.recordingId in available }
            .filter { acceptable(it.source.base.base.features.explicitRating) }
            .map { candidate ->
                AdaptiveCollectionItem(
                    recordingId = candidate.recordingId,
                    rankPosition = candidate.rankPosition,
                    finalScore = candidate.finalScore,
                    reasons = setOf(AdaptiveCollectionReason.CONFIRMED_AUDIBLE_ABSENCE),
                )
            }.take(maxPerCollection).toList()

        val positives = ranked.asSequence()
            .filter { (it.base.base.features.explicitRating ?: Double.NEGATIVE_INFINITY) >= 4.0 }
            .filter { acceptable(it.base.base.features.explicitRating) }
            .map { candidate ->
                AdaptiveCollectionItem(
                    recordingId = candidate.base.base.recordingId,
                    rankPosition = positions.getValue(candidate.base.base.recordingId),
                    finalScore = candidate.finalScore,
                    reasons = setOf(AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING),
                )
            }.take(maxPerCollection).toList()

        val exploratory = ranked.asSequence()
            .filter { it.explorationReasons.contains(ExplorationReason.UNDEREXPOSED_PROMOTION) }
            .filter { acceptable(it.base.base.features.explicitRating) }
            .map { candidate ->
                AdaptiveCollectionItem(
                    recordingId = candidate.base.base.recordingId,
                    rankPosition = positions.getValue(candidate.base.base.recordingId),
                    finalScore = candidate.finalScore,
                    reasons = setOf(AdaptiveCollectionReason.EXPLORATION_PROMOTION),
                )
            }.take(maxPerCollection).toList()

        return listOf(
            AdaptiveCollectionPreview(AdaptiveCollectionKind.REDISCOVERY, rediscovered),
            AdaptiveCollectionPreview(AdaptiveCollectionKind.EXPLICIT_POSITIVES, positives),
            AdaptiveCollectionPreview(AdaptiveCollectionKind.CONTROLLED_EXPLORATION, exploratory),
        )
    }
}
