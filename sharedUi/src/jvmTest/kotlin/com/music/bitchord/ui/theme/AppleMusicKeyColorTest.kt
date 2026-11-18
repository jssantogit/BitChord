package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** AM-DECOMP Android 7.0: key-color resource variants, not guessed popup colors. */
class AppleMusicKeyColorTest {
    @Test fun selectedUiUsesTheDocumentedLightAndNightBlueTokens() {
        assertEquals(Color(0xFF0088FF), AppleMusicLightKey)
        assertEquals(Color(0xFF0091FF), AppleMusicDarkKey)
        assertEquals(AppleMusicLightKey, appleMusicKeyColor(dark = false))
        assertEquals(AppleMusicDarkKey, appleMusicKeyColor(dark = true))
    }
}
