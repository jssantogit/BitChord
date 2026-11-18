package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkBackdropThemePolicyTest {
    @Test fun darkArtworkPageKeepsDarkGradientIndependentOfPhoneTheme() {
        val alphas = artworkBackdropWashAlphas(Color(0xFF0E3333))
        assertEquals(0.40f, alphas.top, 0.0001f)
        assertEquals(0.55f, alphas.middle, 0.0001f)
        assertEquals(0.85f, alphas.lower, 0.0001f)
    }

    @Test fun lightArtworkPageKeepsLightGradientIndependentOfPhoneTheme() {
        val alphas = artworkBackdropWashAlphas(Color(0xFFE8EFE8))
        assertEquals(0.08f, alphas.top, 0.0001f)
        assertEquals(0.25f, alphas.middle, 0.0001f)
        assertEquals(0.72f, alphas.lower, 0.0001f)
    }
}
