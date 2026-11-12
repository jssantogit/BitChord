package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Selection uses the recovered Apple key color, not Material primary. */
class AppleSelectionAccentTest {
    @Test fun selectedMarkIsBlueInLightAndDarkThemes() {
        val neutral = Color(0xFF8E8E93)
        assertEquals(AppleMusicLightKey, appleSelectionAccentColor(true, false, neutral))
        assertEquals(AppleMusicDarkKey, appleSelectionAccentColor(true, true, neutral))
    }

    @Test fun inactiveMarkRemainsNeutralInEitherTheme() {
        val neutral = Color(0xFF8E8E93)
        assertEquals(neutral, appleSelectionAccentColor(false, false, neutral))
        assertEquals(neutral, appleSelectionAccentColor(false, true, neutral))
    }
}
