package com.music.bitchord.data.listening

import kotlin.math.abs

/**
 * Pure, first-party integrity inspection for L4-L7 snapshots. This is not a
 * diagnostic tracer: no events, ring buffer, logging, provider IDs or writes.
 */
internal data class PreferenceCollectionIntegritySnapshot(
    val availableRecordingIds: Set<String>,
    val currentRatings: List<RecordingRating>,
    val inferredPreferences: List<CanonicalInferredPreferenceSummary>,
    val ruleItems: List<RuleBasedSmartPlaylistItem>,
    val adaptiveCollections: List<AdaptiveCollectionPreview>,
)

internal enum class PreferenceCollectionIntegrityIssue {
    EXCESSIVE_INPUT,
    DUPLICATE_CANONICAL_ITEM,
    UNAVAILABLE_CANONICAL_ITEM,
    NEGATIVE_RATING_INCLUDED,
    INVALID_COVERAGE,
    NONFINITE_SCORE,
    INVALID_CANONICAL_ID,
}

internal data class PreferenceCollectionIntegrityResult(
    val isValid: Boolean,
    val issues: Set<PreferenceCollectionIntegrityIssue>,
    val availableCount: Int,
    val ratedCount: Int,
    val evaluatedCollectionItems: Int,
)

/**
 * Deterministic linear-time validation, bounded to a snapshot. Empty and stale
 * data are reported without attempting remediation or inventing preference.
 * Result contains aggregate counts and typed issue codes ONLY; no raw IDs,
 * source keys, private metadata, playback facts, or external telemetry.
 */
internal class PreferenceSmartCollectionIntegrityEvaluator(
    private val maximumItems: Int = 5_000,
) {
    init { require(maximumItems in 1..50_000) }

    fun evaluate(snapshot: PreferenceCollectionIntegritySnapshot): PreferenceCollectionIntegrityResult {
        val issues = linkedSetOf<PreferenceCollectionIntegrityIssue>()
        val allItems = snapshot.ruleItems.size +
            snapshot.adaptiveCollections.sumOf { it.items.size }
        val allSize = snapshot.availableRecordingIds.size + snapshot.currentRatings.size +
            snapshot.inferredPreferences.size + allItems
        if (allSize > maximumItems) {
            issues += PreferenceCollectionIntegrityIssue.EXCESSIVE_INPUT
            return PreferenceCollectionIntegrityResult(
                false, issues, snapshot.availableRecordingIds.size,
                snapshot.currentRatings.size, allItems,
            )
        }
        if (snapshot.availableRecordingIds.any(String::isBlank) ||
            snapshot.currentRatings.any { it.recordingId.isBlank() } ||
            snapshot.inferredPreferences.any { it.targetId.isBlank() }
        ) issues += PreferenceCollectionIntegrityIssue.INVALID_CANONICAL_ID

        val ratingsById = snapshot.currentRatings.associateBy { it.recordingId }
        if (ratingsById.size != snapshot.currentRatings.size) {
            issues += PreferenceCollectionIntegrityIssue.DUPLICATE_CANONICAL_ITEM
        }
        if (snapshot.currentRatings.any { !it.value.isFinite() }) {
            issues += PreferenceCollectionIntegrityIssue.NONFINITE_SCORE
        }
        for (projection in snapshot.inferredPreferences) {
            val rated = projection.ratedRecordingCount
            val total = projection.totalRecordingCount
            val expectedCoverage = if (total <= 0) 0.0 else rated.toDouble() / total
            if (rated < 0 || total < 0 || rated > total ||
                !projection.coverage.isFinite() ||
                abs(projection.coverage - expectedCoverage) > 1e-8 ||
                projection.evidenceRecordingIds.distinct().size != projection.evidenceRecordingIds.size ||
                projection.evidenceRecordingIds.size != rated ||
                (rated == 0 && projection.inferredRating != null) ||
                (rated > 0 && (projection.inferredRating == null ||
                    !projection.inferredRating.isFinite() ||
                    projection.inferredRating !in 1.0..5.0))
            ) issues += PreferenceCollectionIntegrityIssue.INVALID_COVERAGE
        }
        fun checkItem(id: String) {
            if (id.isBlank()) issues += PreferenceCollectionIntegrityIssue.INVALID_CANONICAL_ID
            if (id !in snapshot.availableRecordingIds) {
                issues += PreferenceCollectionIntegrityIssue.UNAVAILABLE_CANONICAL_ITEM
            }
            val rating = ratingsById[id]?.value
            if (rating != null && rating.isFinite() && rating < 3.0) {
                issues += PreferenceCollectionIntegrityIssue.NEGATIVE_RATING_INCLUDED
            }
        }
        if (snapshot.ruleItems.map { it.recordingId }.distinct().size != snapshot.ruleItems.size) {
            issues += PreferenceCollectionIntegrityIssue.DUPLICATE_CANONICAL_ITEM
        }
        snapshot.ruleItems.forEach {
            checkItem(it.recordingId)
            if (it.explicitRating != null && !it.explicitRating.isFinite()) {
                issues += PreferenceCollectionIntegrityIssue.NONFINITE_SCORE
            }
        }
        snapshot.adaptiveCollections.forEach { collection ->
            if (collection.items.map { it.recordingId }.distinct().size != collection.items.size) {
                issues += PreferenceCollectionIntegrityIssue.DUPLICATE_CANONICAL_ITEM
            }
            collection.items.forEach { item ->
                checkItem(item.recordingId)
                if (!item.finalScore.isFinite()) issues += PreferenceCollectionIntegrityIssue.NONFINITE_SCORE
            }
        }
        return PreferenceCollectionIntegrityResult(
            isValid = issues.isEmpty(),
            issues = issues,
            availableCount = snapshot.availableRecordingIds.size,
            ratedCount = snapshot.currentRatings.size,
            evaluatedCollectionItems = allItems,
        )
    }
}
