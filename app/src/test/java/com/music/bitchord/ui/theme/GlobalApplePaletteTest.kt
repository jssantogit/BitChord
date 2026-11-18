package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Prevent unconstrained Material3 purple/pink defaults from leaking into native surfaces. */
class GlobalApplePaletteTest {
    @Test fun commonThemeUsesAnExplicitKeyBlueWithoutChangingNeutralPrimaryText() {
        assertEquals(Color.White, DarkColors.primary)
        assertEquals(Color.Black, LightColors.primary)
        assertEquals(AppleMusicDarkKey, DarkColors.secondary)
        assertEquals(AppleMusicLightKey, LightColors.secondary)
        assertEquals(Color.White, DarkColors.onSecondary)
        assertEquals(Color.White, LightColors.onSecondary)
    }

    @Test fun materialContainerAndTintDefaultsAreExplicitNeutralAdaptations() {
        assertEquals(Color.Transparent, DarkColors.surfaceTint)
        assertEquals(Color.Transparent, LightColors.surfaceTint)
        assertEquals(Color(0xFF1C1C1E), DarkColors.secondaryContainer)
        assertEquals(Color(0xFFF2F2F7), LightColors.secondaryContainer)
        assertNotEquals(Color(0xFFEFB8C8), DarkColors.tertiary)
        assertNotEquals(Color(0xFF7D5260), LightColors.tertiary)
    }
}
