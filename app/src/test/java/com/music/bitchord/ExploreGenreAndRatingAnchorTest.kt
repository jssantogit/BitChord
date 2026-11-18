package com.music.bitchord

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.music.bitchord.ui.screens.anchoredRatingCardPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreGenreAndRatingAnchorTest {
    @Test fun genreUsesOneGlobalBackButtonWhileExploreRootDoesNot() {
        assertTrue(shouldShowExploreGenreTopBarBack(1, 1, true, false))
        assertFalse(shouldShowExploreGenreTopBarBack(1, 1, false, false))
        assertFalse(shouldShowExploreGenreTopBarBack(1, 1, true, true))
        assertFalse(shouldShowExploreGenreTopBarBack(0, 1, true, false))
    }

    @Test fun ratingOpensBelowTheTopRightStarAndRespectsScreenMargins() {
        assertEquals(
            IntOffset(30, 100),
            anchoredRatingCardPosition(
                anchor = IntRect(300, 50, 340, 90),
                popup = IntSize(310, 220),
                window = IntSize(360, 640),
                layoutDirection = LayoutDirection.Ltr,
                gap = 10,
                margin = 12,
            ),
        )
    }

    @Test fun ratingFlipsAboveLowAnchorsWithoutClippingAndSupportsRtl() {
        assertEquals(
            IntOffset(12, 230),
            anchoredRatingCardPosition(
                anchor = IntRect(18, 460, 58, 500),
                popup = IntSize(310, 220),
                window = IntSize(360, 640),
                layoutDirection = LayoutDirection.Rtl,
                gap = 10,
                margin = 12,
            ),
        )
    }
}
