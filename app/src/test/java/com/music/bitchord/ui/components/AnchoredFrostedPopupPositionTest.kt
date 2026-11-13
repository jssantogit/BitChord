package com.music.bitchord.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class AnchoredFrostedPopupPositionTest {
    @Test fun sourceNearLeftStaysBelowItsOwnButtonInsteadOfRightCorner() {
        val pos = anchoredFrostedMenuPosition(
            anchor = IntRect(50, 80, 90, 120),
            popup = IntSize(230, 200),
            overlay = IntSize(360, 760),
            overlayOrigin = IntOffset.Zero,
            gap = 12,
            margin = 12,
        )
        assertEquals(IntOffset(12, 132), pos)
    }

    @Test fun sourceNearBottomFlipsMenuAboveAndPreservesItsHorizontalAnchor() {
        val pos = anchoredFrostedMenuPosition(
            anchor = IntRect(260, 600, 300, 640),
            popup = IntSize(240, 260),
            overlay = IntSize(360, 760),
            overlayOrigin = IntOffset.Zero,
            gap = 12,
            margin = 12,
        )
        assertEquals(IntOffset(60, 328), pos)
    }

    @Test fun overlayWindowOriginAndNarrowPhoneBoundsAreRespected() {
        val pos = anchoredFrostedMenuPosition(
            anchor = IntRect(260, 52, 300, 92),
            popup = IntSize(280, 300),
            overlay = IntSize(320, 600),
            overlayOrigin = IntOffset(0, 24),
            gap = 12,
            margin = 12,
        )
        assertEquals(IntOffset(12, 80), pos)
    }
}
