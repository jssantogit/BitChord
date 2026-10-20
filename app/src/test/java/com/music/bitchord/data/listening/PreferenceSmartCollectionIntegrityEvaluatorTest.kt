package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSmartCollectionIntegrityEvaluatorTest {
    private val eval = PreferenceSmartCollectionIntegrityEvaluator()

    private fun summary() = CanonicalInferredPreferenceSummary(
        targetType = CanonicalPreferenceTargetType.RELEASE,
        targetId = "rel:a",
        inferredRating = 4.5,
        ratedRecordingCount = 2,
        totalRecordingCount = 3,
        coverage = 2.0 / 3.0,
        confidence = CanonicalPreferenceConfidence.MEDIUM,
        evidenceRecordingIds = listOf("rec:a", "rec:b"),
    )

    @Test fun canonicalReadOnlySnapshotsPassDeterministically() {
        val snapshot = PreferenceCollectionIntegritySnapshot(
            availableRecordingIds = setOf("rec:a", "rec:b", "rec:c"),
            currentRatings = listOf(RecordingRating("rec:a", 5.0, 1L)),
            inferredPreferences = listOf(summary()),
            ruleItems = listOf(RuleBasedSmartPlaylistItem(
                "rec:a", setOf(RuleBasedInclusionReason.EXPLICIT_RATING), 5.0, 2,
            )),
            adaptiveCollections = listOf(AdaptiveCollectionPreview(
                AdaptiveCollectionKind.EXPLICIT_POSITIVES,
                listOf(AdaptiveCollectionItem(
                    "rec:a", 1, 55.0, setOf(AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING),
                )),
            )),
        )
        val first = eval.evaluate(snapshot)
        assertTrue(first.isValid)
        assertTrue(first.issues.isEmpty())
        assertEquals(first, eval.evaluate(snapshot))
    }

    @Test fun staleAndDuplicateItemsAndCorruptCoverageAreVisibleWithoutLeakingProviderData() {
        val snapshot = PreferenceCollectionIntegritySnapshot(
            availableRecordingIds = setOf("rec:a"),
            currentRatings = listOf(RecordingRating("rec:a", 2.0, 1L)),
            inferredPreferences = listOf(summary().copy(coverage = 1.0)),
            ruleItems = listOf(
                RuleBasedSmartPlaylistItem("rec:a", emptySet(), 2.0, 0),
                RuleBasedSmartPlaylistItem("rec:a", emptySet(), 2.0, 0),
            ),
            adaptiveCollections = listOf(AdaptiveCollectionPreview(
                AdaptiveCollectionKind.CONTROLLED_EXPLORATION,
                listOf(AdaptiveCollectionItem("rec:gone", 2, Double.NaN, emptySet())),
            )),
        )
        val result = eval.evaluate(snapshot)
        assertFalse(result.isValid)
        assertTrue(result.issues.contains(PreferenceCollectionIntegrityIssue.INVALID_COVERAGE))
        assertTrue(result.issues.contains(PreferenceCollectionIntegrityIssue.DUPLICATE_CANONICAL_ITEM))
        assertTrue(result.issues.contains(PreferenceCollectionIntegrityIssue.UNAVAILABLE_CANONICAL_ITEM))
        assertTrue(result.issues.contains(PreferenceCollectionIntegrityIssue.NONFINITE_SCORE))
        assertTrue(result.issues.contains(PreferenceCollectionIntegrityIssue.NEGATIVE_RATING_INCLUDED))
        assertFalse(result.toString().contains("sourceKey"))
    }

    @Test fun boundedEvaluationFailsClosedWithoutDroppingTailEvidence() {
        val oversized = (0..5_001).map { "rec:$it" }.toSet()
        val result = eval.evaluate(PreferenceCollectionIntegritySnapshot(
            availableRecordingIds = oversized,
            currentRatings = emptyList(),
            inferredPreferences = emptyList(),
            ruleItems = emptyList(),
            adaptiveCollections = emptyList(),
        ))
        assertFalse(result.isValid)
        assertTrue(PreferenceCollectionIntegrityIssue.EXCESSIVE_INPUT in result.issues)
    }
}
