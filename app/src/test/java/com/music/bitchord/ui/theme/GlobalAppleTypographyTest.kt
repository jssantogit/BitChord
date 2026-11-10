package com.music.bitchord.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cross-screen typography contract, informed by am-descomp's focused Android XML evidence. */
class GlobalAppleTypographyTest {
    @Test fun commonTextRolesFollowOneSFProBasedScale() {
        assertEquals(SFProDisplay, BitChordTypography.titleMedium.fontFamily)
        assertEquals(SFProDisplay, BitChordTypography.bodyLarge.fontFamily)
        assertEquals(SFProDisplay, BitChordTypography.labelLarge.fontFamily)
        assertEquals(28.sp, BitChordTypography.headlineLarge.fontSize)
        assertEquals(20.sp, BitChordTypography.headlineMedium.fontSize)
        assertEquals(17.sp, BitChordTypography.titleMedium.fontSize)
        assertEquals(17.sp, BitChordTypography.bodyLarge.fontSize)
        assertEquals(FontWeight.W700, BitChordTypography.headlineLarge.fontWeight)
    }
}
