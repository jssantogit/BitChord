package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the #212 source-spotlight contract while #219 is reconciled. */
class ProviderReaHeldSpotlightCompatibilityTest {
    @Test fun heldCardKeepsItsOriginalSizeAndPosition() {
        val original = Rect(16f, 340f, 400f, 424f)
        val preview = heldPreviewFixedFrame(original, Offset.Zero)
        assertEquals(16, preview.x)
        assertEquals(340, preview.y)
        assertEquals(384, preview.width)
        assertEquals(84, preview.height)
        assertEquals(1f, preview.scale)
        assertEquals(original, heldSpotlightCutout(original, Offset.Zero, 430f, 960f))
        assertEquals(Color.Transparent, heldPreviewBackingColor())
    }

    @Test fun bottomChromeAndAccessibilityStillConstrainOnlyTheMenu() {
        assertEquals(770, heldMenuVisibleBottom(1000, 30, 200))
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = 670f, sourceHeight = 90f, viewportTop = 40,
            viewportBottom = 770, gap = 10, requestedMenuHeight = 500,
        )
        assertEquals(670f, placement.previewTop)
        assertFalse(placement.menuBelow)
        assertTrue(placement.menuMaxHeight > 0)
        assertFalse(heldActionsUseHaze(reduceDynamicBlur = true))
    }

    @Test fun actionPlateIsIndependentOfArtworkTint() {
        assertEquals(Color(0xFF29292D), heldContextPlateColor(Color.Black, Color.Green))
        assertEquals(Color(0xFFF5F5F7), heldContextPlateColor(Color.White, Color.Magenta))
    }
}
