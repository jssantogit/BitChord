package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import com.music.bitchord.ui.player.AppleQueueVectors
import org.junit.Test

/**
 * New evaluation UI must reuse the expanded player's AppleQueueVectors
 * rather than Material Star, StarBorder and StarHalf glyphs. The HALF state
 * is rendered by clipping the SAME filled queue vector at its midpoint.
 */
class AppleRatingStarGlyphTest {
    @Test fun fiveStarRatingSupportsHalfStepsWithStableEmptyAndFullStates() {
        assertEquals(AppleRatingStarFill.EMPTY, appleRatingStarFill(0.0, 0))
        assertEquals(AppleRatingStarFill.FULL, appleRatingStarFill(1.0, 0))
        assertEquals(AppleRatingStarFill.HALF, appleRatingStarFill(1.5, 1))
        assertEquals(AppleRatingStarFill.FULL, appleRatingStarFill(2.0, 1))
        assertEquals(AppleRatingStarFill.HALF, appleRatingStarFill(2.5, 2))
        assertEquals(AppleRatingStarFill.EMPTY, appleRatingStarFill(2.5, 3))
        assertEquals(AppleRatingStarFill.HALF, appleRatingStarFill(4.5, 4))
        assertEquals(AppleRatingStarFill.FULL, appleRatingStarFill(5.0, 4))
    }

    @Test fun ratingEditorUsesStandalonePlayerStarContoursWithoutCircularPlatter() {
        val outline = AppleQueueVectors.RatingStarOutline
        val filled = AppleQueueVectors.RatingStarFilled
        assertEquals("apple_queue_rating_star_outline", outline.name)
        assertEquals("apple_queue_rating_star_filled", filled.name)
        assertEquals(28f, outline.viewportWidth, 0.0001f)
        assertEquals(28f, outline.viewportHeight, 0.0001f)
        assertEquals(28f, filled.viewportWidth, 0.0001f)
        assertEquals(28f, filled.viewportHeight, 0.0001f)
    }

    @Test fun invalidAndNonfiniteRatingsCannotPaintAnySelectedStars() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5, 5.5)) {
            for (index in 0..4) {
                assertEquals(AppleRatingStarFill.EMPTY, appleRatingStarFill(invalid, index))
            }
        }
        assertEquals(AppleRatingStarFill.EMPTY, appleRatingStarFill(5.0, -1))
        assertEquals(AppleRatingStarFill.EMPTY, appleRatingStarFill(5.0, 5))
    }
}
