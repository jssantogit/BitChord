package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class AppleQualityVectorCompatibilityTest {
    @Test fun extractedLosslessGlyphUsesNativeComposeFourteenByNineGeometry() {
        val icon = AppleQualityVectors.Lossless
        assertEquals(14.dp, icon.defaultWidth)
        assertEquals(9.dp, icon.defaultHeight)
        assertEquals(14f, icon.viewportWidth)
        assertEquals(9f, icon.viewportHeight)
        assertTrue(icon.name.contains("lossless"))
    }
}
