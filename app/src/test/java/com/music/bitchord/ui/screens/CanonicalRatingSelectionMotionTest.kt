package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.music.bitchord.ui.components.CompactRatingStyle
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
    @Test fun draggingLeftCanClearAllStarsWithoutChangingHalfStepPrecision() {
        assertEquals(0.0, ratingSelectionFromDrag(-12f, 250f), 0.0)
        assertEquals(0.0, ratingSelectionFromDrag(0f, 250f), 0.0)
        assertEquals(2.0, ratingSelectionFromDrag(100f, 250f), 0.0)
        assertEquals(2.5, ratingSelectionFromDrag(125f, 250f), 0.0)
        assertEquals(5.0, ratingSelectionFromDrag(250f, 250f), 0.0)
    }

    @Test fun fractionalColorRatingBlendsBothAdjacentTonesButNumericStaysNeutral() {
        val colored = ratingEditorGradientStops(2.5, CompactRatingStyle.COLOR)
        assertEquals(4, colored.size)
        assertEquals(0f, colored.first().first, 0f)
        assertEquals(1f, colored.last().first, 0f)
        assertEquals(false, colored.first().second == colored.last().second)
        assertEquals(emptyList<Pair<Float, androidx.compose.ui.graphics.Color>>(),
            ratingEditorGradientStops(2.5, CompactRatingStyle.NUMERIC))
        val monochrome = androidx.compose.ui.graphics.Color.White
        assertEquals(monochrome,
            ratingEditorStarTint(CompactRatingStyle.NUMERIC, 2.5, true, monochrome))
        assertEquals(monochrome,
            ratingEditorStarTint(CompactRatingStyle.NUMERIC, 2.5, false, monochrome))
    }

    @Test fun numericEditorPlacesTheRatingOnlyInsideItsLastSelectedStar() {
        assertEquals(-1, lastRatedStarIndex(0.0))
        assertEquals(1, lastRatedStarIndex(2.0))
        assertEquals(1, lastRatedStarIndex(1.5))
        assertEquals(4, lastRatedStarIndex(5.0))
    }

}
