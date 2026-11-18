package com.music.bitchord.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Selected rows in the choice sheet use the Apple key color, not Material primary. */
class WizardChoiceSelectionTintTest {
    @Test fun selectedChoiceUsesTheProvidedAppleKeyColor() {
        val blue = Color(0xFF0088FF)
        val neutral = Color(0xFF8E8E93)
        assertEquals(blue, wizardChoiceSelectionTint(true, keyColor = blue, neutralColor = neutral))
    }

    @Test fun unselectedChoiceRemainsNeutral() {
        val blue = Color(0xFF0088FF)
        val neutral = Color(0xFF8E8E93)
        assertEquals(neutral, wizardChoiceSelectionTint(false, keyColor = blue, neutralColor = neutral))
    }
}
