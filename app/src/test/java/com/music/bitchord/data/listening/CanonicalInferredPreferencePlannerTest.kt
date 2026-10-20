package com.music.bitchord.data.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalInferredPreferencePlannerTest {
    private val planner = CanonicalInferredPreferencePlanner()

    @Test fun inferredValueCoverageAndConfidenceRemainDistinct() {
        val result = planner.project(
            targetType = CanonicalPreferenceTargetType.RELEASE,
            targetId = "release:canonical",
            memberRecordingIds = listOf("rec:c", "rec:a", "rec:b"),
            explicitRatings = listOf(
                RecordingRating("rec:a", 5.0, 1L),
                RecordingRating("rec:b", 2.0, 2L),
            ),
        )
        assertEquals(3.5, result.inferredRating!!, 0.0)
        assertEquals(2, result.ratedRecordingCount)
        assertEquals(3, result.totalRecordingCount)
        assertEquals(2.0 / 3.0, result.coverage, 0.00001)
        assertEquals(CanonicalPreferenceConfidence.MEDIUM, result.confidence)
        assertEquals(listOf("rec:a", "rec:b"), result.evidenceRecordingIds)
    }

    @Test fun neverRatedIsUnknownNotNeutralAndDuplicatesCannotInflateCoverage() {
        val result = planner.project(
            targetType = CanonicalPreferenceTargetType.ARTIST,
            targetId = "artist:canonical",
            memberRecordingIds = listOf("rec:b", "rec:b", "rec:a"),
            explicitRatings = emptyList(),
        )
        assertNull(result.inferredRating)
        assertEquals(0, result.ratedRecordingCount)
        assertEquals(2, result.totalRecordingCount)
        assertEquals(0.0, result.coverage, 0.0)
        assertEquals(CanonicalPreferenceConfidence.INSUFFICIENT, result.confidence)
    }

    @Test fun outOfGroupRatingsAndNonFiniteValuesNeverPolluteProjection() {
        val result = planner.project(
            targetType = CanonicalPreferenceTargetType.ARTIST,
            targetId = "artist:exact-identity",
            memberRecordingIds = listOf("rec:a"),
            explicitRatings = listOf(
                RecordingRating("rec:outside", 5.0, 1L),
                RecordingRating("rec:a", Double.NaN, 2L),
            ),
        )
        assertNull(result.inferredRating)
        assertTrue(result.evidenceRecordingIds.isEmpty())
        assertEquals(CanonicalPreferenceConfidence.INSUFFICIENT, result.confidence)
    }
}
