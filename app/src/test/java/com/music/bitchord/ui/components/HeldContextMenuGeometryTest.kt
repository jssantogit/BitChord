package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lifted menu must never disappear behind the Android navigation capsule. */
class HeldContextMenuGeometryTest {
    @Test fun floatingBarsAreExcludedFromTheHeldMenuViewport() {
        assertEquals(770, heldMenuVisibleBottom(
            viewportHeight = 1000, systemBottomInset = 30, chromeReserve = 200,
        ))
    }

    @Test fun manyActionsKeepEnoughRoomForReadingAndACompactPreview() {
        val menuHeight = heldMenuMaxHeight(availableHeight = 600, minPreview = 100, gap = 12)
        assertTrue(menuHeight >= 400)
        assertTrue(menuHeight + 100 + 12 <= 600)
        val scale = heldMenuPreviewScale(
            plateWidth = 400, plateHeight = 420, viewportWidth = 430,
            margin = 20, availableHeight = 600, menuHeight = menuHeight, gap = 12,
        )
        assertTrue(scale < 0.6f)
        assertTrue(420 * scale + menuHeight + 12 <= 600)
    }

    @Test fun ordinaryShortMenusDoNotUnnecessarilyShrinkSmallRows() {
        assertEquals(1f, heldMenuPreviewScale(
            plateWidth = 220, plateHeight = 100, viewportWidth = 430,
            margin = 20, availableHeight = 600, menuHeight = 240, gap = 12,
        ))
    }

    @Test fun blurIsEnabledForHeldItemsExceptWhenAccessibilityDisablesIt() {
        assertTrue(shouldBlurHeldContextBackdrop(heldVisible = true, reduceDynamicBlur = false))
        assertFalse(shouldBlurHeldContextBackdrop(heldVisible = false, reduceDynamicBlur = false))
        assertFalse(shouldBlurHeldContextBackdrop(heldVisible = true, reduceDynamicBlur = true))
    }

    @Test fun parentBlurringAndHazeSamplingAreMutuallyExclusive() {
        assertFalse(heldMenuNeedsOwnHazeSource(
            hostProvidesBlur = true, reduceDynamicBlur = false,
        ))
        assertTrue(heldMenuNeedsOwnHazeSource(
            hostProvidesBlur = false, reduceDynamicBlur = false,
        ))
        assertFalse(heldMenuNeedsOwnHazeSource(
            hostProvidesBlur = false, reduceDynamicBlur = true,
        ))
    }
}
