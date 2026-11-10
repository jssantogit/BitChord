package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalRatingSelectionMotionTest {
    @Test fun halfStarAndFullStarPulseOnlyTheStarTheUserTouched() {
        assertEquals(0, ratingSelectionPulseIndex(1.0, touched = true, reducedMotion = false))
        assertEquals(1, ratingSelectionPulseIndex(1.5, touched = true, reducedMotion = false))
        assertEquals(1, ratingSelectionPulseIndex(2.0, touched = true, reducedMotion = false))
        assertEquals(4, ratingSelectionPulseIndex(4.5, touched = true, reducedMotion = false))
        assertEquals(4, ratingSelectionPulseIndex(5.0, touched = true, reducedMotion = false))
    }

    @Test fun ratingDialogHasSubtleOpeningButNoScaleOrAlphaChangeInReducedMotion() {
        val start = ratingDialogEntranceFrame(0f, reducedMotion = false)
        assertEquals(0.96f, start.alpha, 0.0001f)
        assertEquals(0.97f, start.scale, 0.0001f)
        val end = ratingDialogEntranceFrame(1f, reducedMotion = false)
        assertEquals(1f, end.alpha, 0.0001f)
        assertEquals(1f, end.scale, 0.0001f)
        assertEquals(end, ratingDialogEntranceFrame(0f, reducedMotion = true))
        assertEquals(end, ratingDialogEntranceFrame(Float.NaN, reducedMotion = false))
    }

    @Test fun persistedRatingsAndReducedMotionDoNotPulseOnOpening() {
        assertNull(ratingSelectionPulseIndex(4.5, touched = false, reducedMotion = false))
        assertNull(ratingSelectionPulseIndex(4.5, touched = true, reducedMotion = true))
        assertNull(ratingSelectionPulseIndex(0.0, touched = true, reducedMotion = false))
        assertNull(ratingSelectionPulseIndex(Double.NaN, touched = true, reducedMotion = false))
        assertNull(ratingSelectionPulseIndex(Double.POSITIVE_INFINITY, touched = true, reducedMotion = false))
    }
}
