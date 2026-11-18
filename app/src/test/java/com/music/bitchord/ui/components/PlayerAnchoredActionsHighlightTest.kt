package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import com.music.bitchord.ui.player.originalPortraitNowPlayingOverflowGlyph
import org.junit.Test

/** The expanded player menu's highlighted ⋯ sits on its real launcher,
 * even when a dialog window has a nonzero screen origin. */
class PlayerAnchoredActionsHighlightTest {
    @Test fun expandedCollectionOverflowUsesSoftFillAndNoDuplicatedWhiteBorder() {
        assertEquals(Color.White.copy(alpha = 0.14f),
            com.music.bitchord.nativeCollectionOverflowActiveFill(true, Color.White))
        assertEquals(Color.Transparent,
            com.music.bitchord.nativeCollectionOverflowActiveFill(false, Color.White))
    }

    @Test fun playerOverflowAndRatingUseTheSameNeutralActionPlate() {
        assertEquals(true, playerAnchoredMenuUsesSharedRatingPlate(highlightLauncher = false))
        assertEquals(true, playerAnchoredMenuUsesSharedRatingPlate(highlightLauncher = true))
    }

    @Test fun animatedMenuPlateNeverMixesItsLabelsWithPlayerControls() {
        // Previously alpha followed an entrance tween 0..1, revealing player
        // controls through menu rows and creating overlapping text/icon ghosts.
        assertEquals(0f, playerAnchoredMenuPlateAlpha(measured = false), 0f)
        assertEquals(1f, playerAnchoredMenuPlateAlpha(measured = true), 0f)
        assertEquals(0.975f, playerAnchoredMenuPlateScale(0f), 0.0001f)
        assertEquals(0.9875f, playerAnchoredMenuPlateScale(0.5f), 0.0001f)
        assertEquals(1f, playerAnchoredMenuPlateScale(1f), 0.0001f)
    }

    @Test fun highlightedMenuUsesExactlyTheOriginalPlayerVector() {
        assertSame(originalPortraitNowPlayingOverflowGlyph(), playerAnchoredMenuHighlightGlyph())
    }

    @Test fun buttonHighlightFollowsExactLauncherCoordinates() {
        assertEquals(
            IntOffset(318, 64),
            playerAnchoredMenuHighlightPosition(
                anchor = IntRect(328, 88, 368, 128),
                overlayOrigin = IntOffset(10, 24),
                overlaySize = IntSize(400, 800),
            ),
        )
    }
    @Test fun outOfBoundsAnchorCannotDrawAnOffscreenSelectedButton() {
        assertEquals(
            IntOffset(360, 0),
            playerAnchoredMenuHighlightPosition(
                anchor = IntRect(900, -500, 940, -460),
                overlayOrigin = IntOffset.Zero,
                overlaySize = IntSize(400, 800),
            ),
        )
    }
}
