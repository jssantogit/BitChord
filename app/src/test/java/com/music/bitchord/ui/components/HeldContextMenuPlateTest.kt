package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldContextMenuPlateTest {
    @Test fun neutralPagesKeepReadableDarkAndLightPlates() {
        assertEquals(Color.Black.copy(alpha = 0.97f),
            heldContextPlateColor(Color.Black, null))
        assertEquals(Color.White.copy(alpha = 0.97f),
            heldContextPlateColor(Color.White, null))
    }

    @Test fun artworkPagesTintBothLiftedPreviewAndActionPanel() {
        val tealAlbumSurface = Color(0xFF214D4C)
        assertEquals(tealAlbumSurface.copy(alpha = 0.97f),
            heldContextPlateColor(Color(0xFF0E3333), tealAlbumSurface))
    }
}
