package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldOriginVisibilityTest {
    @Test fun fullyVisibleSourceCanOpenContextMenu() {
        assertTrue(heldOriginFullyVisible(Rect(20f, 100f, 220f, 340f), 200, 240))
    }

    @Test fun clippedCarouselCardMustNotStretchSnapshotIntoVisibleSliver() {
        assertFalse(heldOriginFullyVisible(Rect(340f, 100f, 390f, 340f), 200, 240))
    }

    @Test fun partiallyClippedVerticalRowCannotUseIncompleteCapture() {
        assertFalse(heldOriginFullyVisible(Rect(20f, 100f, 220f, 170f), 200, 90))
    }

    @Test fun zeroAndInvalidBoundsNeverOpenMenu() {
        assertFalse(heldOriginFullyVisible(Rect.Zero, 0, 0))
        assertFalse(heldOriginFullyVisible(Rect(10f, 10f, 5f, 20f), 100, 100))
    }
}
