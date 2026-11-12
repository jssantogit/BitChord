package com.music.bitchord.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle
import com.music.bitchord.ui.components.AppleTextRoles
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

    @Test fun sharedTextRolesReuseTheGlobalThemeWithoutHardcodedDuplicatedInputs() {
        val body = BitChordTypography.bodyMedium
        val category = BitChordTypography.bodyLarge
        // Ordinary fields and library category names must follow the one theme
        // scale. The root Library title remains a deliberate 36sp role.
        assertEquals(body, AppleTextRoles.formInput(body))
        assertEquals(category, AppleTextRoles.categoryLabel(category))
        assertEquals(36.sp, AppleTextRoles.libraryLandingTitle(
            BitChordTypography.headlineLarge,
        ).fontSize)
        assertEquals(FontWeight.W700, AppleTextRoles.libraryLandingTitle(
            BitChordTypography.headlineLarge,
        ).fontWeight)
    }
}
