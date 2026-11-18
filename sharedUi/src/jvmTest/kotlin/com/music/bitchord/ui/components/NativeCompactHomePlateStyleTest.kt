package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCompactHomePlateStyleTest {
    @Test fun theHomePlateMenuUsesLargerRowsWithoutChangingOtherPopups() {
        assertEquals(62, nativeCompactMenuRowHeightDp(homePlateStyle = true))
        assertEquals(54, nativeCompactMenuRowHeightDp(homePlateStyle = false))
    }

    @Test fun darkMenuIsLiftedOutOfAnAmoledBlackPageWithoutWhiteBorder() {
        val plate = nativeCompactPlateColor(isDark = true, artworkElevation = null)
        assertEquals(Color(0xFF29292D), plate)
        assertEquals(1f, plate.alpha)
        assertTrue(plate.luminance() > Color.Black.luminance())
    }

    @Test fun lightMenuRemainsSubtlySeparatedFromWhitePages() {
        val plate = nativeCompactPlateColor(isDark = false, artworkElevation = null)
        assertEquals(Color(0xFFF5F5F7), plate)
        assertEquals(1f, plate.alpha)
        assertTrue(plate.luminance() < Color.White.luminance())
    }

    @Test fun dynamicallyTintedMenusKeepTheExactNeutralLibraryFilterPlate() {
        val albumElevation = Color(0xFF244C52)
        val pinkElevation = Color(0xFFF340A0)
        assertEquals(
            nativeCompactPlateColor(isDark = true, artworkElevation = null),
            nativeCompactPlateColor(isDark = true, artworkElevation = albumElevation),
        )
        assertEquals(
            nativeCompactPlateColor(isDark = false, artworkElevation = null),
            nativeCompactPlateColor(isDark = false, artworkElevation = pinkElevation),
        )
    }

    @Test fun libraryFilterScrimDoesNotHideItsContext() {
        val dark = nativeCompactMenuScrimAlpha(isDark = true)
        val light = nativeCompactMenuScrimAlpha(isDark = false)
        assertTrue(dark in 0.2f..0.35f)
        assertTrue(light in 0.15f..0.3f)
    }

    @Test fun compactPlateVariantHasTheSameNarrowRoundnessAsTheHeldContextMenu() {
        assertEquals(16, nativeCompactMenuCornerRadiusDp(homePlateStyle = true))
        assertEquals(18, nativeCompactMenuCornerRadiusDp(homePlateStyle = false))
    }
}
