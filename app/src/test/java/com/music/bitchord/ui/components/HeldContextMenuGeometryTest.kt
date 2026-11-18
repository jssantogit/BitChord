package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lifted menu must never disappear behind the Android navigation capsule. */
class HeldContextMenuGeometryTest {
    @Test fun liftedPreviewRetainsExactSourceOriginAndSize() {
        val bounds = androidx.compose.ui.geometry.Rect(12f, 420f, 390f, 494f)
        val origin = androidx.compose.ui.geometry.Offset(0f, 25f)
        val frame = heldPreviewFixedFrame(bounds, origin)
        assertEquals(12, frame.x)
        assertEquals(395, frame.y)
        assertEquals(378, frame.width)
        assertEquals(74, frame.height)
        // No progress-dependent scale or translation may change that frame.
        assertEquals(1f, frame.scale)
    }

    @Test fun fullWidthHeldRowNeverPushesItsSelectionEdgeOutsideTheViewport() {
        val frame = heldPreviewFixedFrame(
            androidx.compose.ui.geometry.Rect(0f, 520f, 430f, 592f),
            androidx.compose.ui.geometry.Offset.Zero,
        )
        assertEquals(0, frame.x)
        assertEquals(430, frame.width)
    }

    @Test fun selectedTrackDoesNotExtendItsOutlineOverTheNextRow() {
        val first = heldPreviewFixedFrame(
            androidx.compose.ui.geometry.Rect(16f, 500f, 400f, 572f),
            androidx.compose.ui.geometry.Offset.Zero,
        )
        val next = heldPreviewFixedFrame(
            androidx.compose.ui.geometry.Rect(16f, 572f, 400f, 644f),
            androidx.compose.ui.geometry.Offset.Zero,
        )
        assertEquals(next.y, first.y + first.height)
    }

    @Test fun selectedCardUsesItsExactOriginalBoundsWithNoRingOrShadow() {
        val source = androidx.compose.ui.geometry.Rect(24f, 120f, 344f, 540f)
        val spotlight = heldSpotlightCutout(
            sourceBounds = source,
            overlayOrigin = androidx.compose.ui.geometry.Offset.Zero,
            viewportWidth = 430f,
            viewportHeight = 900f,
        )
        assertEquals(source, spotlight)
        assertEquals(24f, spotlight!!.left)
        assertEquals(344f, spotlight.right)
        assertEquals(540f, spotlight.bottom)
    }

    @Test fun selectedRowIsExcludedFromDimmingWithoutResizingItsTextOrArtwork() {
        val source = androidx.compose.ui.geometry.Rect(16f, 500f, 406f, 580f)
        assertEquals(source, heldSpotlightCutout(
            sourceBounds = source,
            overlayOrigin = androidx.compose.ui.geometry.Offset.Zero,
            viewportWidth = 430f,
            viewportHeight = 1000f,
        ))
    }

    @Test fun spotlightClampsAtScreenEdgesRatherThanDrawingOutsideOrStretching() {
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 10f, 150f, 200f),
            heldSpotlightCutout(
                androidx.compose.ui.geometry.Rect(-10f, 20f, 140f, 210f),
                androidx.compose.ui.geometry.Offset(-10f, 10f),
                viewportWidth = 150f, viewportHeight = 200f,
            ))
        assertEquals(null, heldSpotlightCutout(
            androidx.compose.ui.geometry.Rect(500f, 10f, 600f, 50f),
            androidx.compose.ui.geometry.Offset.Zero,
            viewportWidth = 430f, viewportHeight = 900f,
        ))
    }

    @Test fun menuNeverMovesSourceCardEvenWhenActionsDoNotFitBelow() {
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = 420f, sourceHeight = 110f, viewportTop = 40, viewportBottom = 780,
            gap = 10, requestedMenuHeight = 540,
        )
        assertEquals(420f, placement.previewTop)
        assertFalse(placement.menuBelow)
        assertTrue(placement.menuTop + placement.menuMaxHeight <= 780)
        assertTrue(placement.menuMaxHeight > 0)
    }

    @Test fun bottomCardKeepsOriginalPositionAndScrollsActionsAbove() {
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = 670f, sourceHeight = 90f, viewportTop = 40, viewportBottom = 780,
            gap = 10, requestedMenuHeight = 500,
        )
        assertEquals(670f, placement.previewTop)
        assertFalse(placement.menuBelow)
        assertTrue(placement.menuMaxHeight <= 620)
    }

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
