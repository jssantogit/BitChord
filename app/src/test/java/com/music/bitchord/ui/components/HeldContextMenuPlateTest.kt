package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldContextMenuPlateTest {
    @Test fun heldSelectionUsesTheOriginalArtworkWithoutDecorativePixelEffects() {
        assertEquals(Color.Transparent, heldPreviewBackingColor())
    }

    @Test fun neutralPagesKeepReadableDarkAndLightPlates() {
        assertEquals(nativeCompactPlateColor(isDark = true, artworkElevation = null),
            heldContextPlateColor(Color.Black, null))
        assertEquals(nativeCompactPlateColor(isDark = false, artworkElevation = null),
            heldContextPlateColor(Color.White, null))
    }

    @Test fun longPressActionsUseFrostedMaterialUnlessBlurIsDisabled() {
        assertEquals(true, heldActionsUseHaze(reduceDynamicBlur = false))
        assertEquals(false, heldActionsUseHaze(reduceDynamicBlur = true))
    }

    @Test fun heldSnapshotDoesNotReceiveASecondGreyCardSurface() {
        assertEquals(Color.Transparent, heldPreviewBackingColor())
    }

    @Test fun artworkDoesNotTintTheLongPressActionPlateOrReduceContrast() {
        val tealAlbumSurface = Color(0xFF214D4C)
        assertEquals(nativeCompactPlateColor(isDark = true, artworkElevation = null),
            heldContextPlateColor(Color(0xFF0E3333), tealAlbumSurface))
        assertEquals(nativeCompactPlateColor(isDark = false, artworkElevation = null),
            heldContextPlateColor(Color.White, Color(0xFFFF66BB)))
    }
}
