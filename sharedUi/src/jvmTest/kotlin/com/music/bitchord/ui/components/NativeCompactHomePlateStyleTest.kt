package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCompactHomePlateStyleTest {
    @Test fun theHomePlateMenuUsesLargerRowsWithoutChangingOtherPopups() {
        assertEquals(62, nativeCompactMenuRowHeightDp(homePlateStyle = true))
        assertEquals(54, nativeCompactMenuRowHeightDp(homePlateStyle = false))
    }

    @Test fun artworkPageMenusUseTheirOwnElevatedColorInsteadOfAmoledBlack() {
        val albumElevation = Color(0xFF244C52)
        assertEquals(albumElevation.copy(alpha = 0.96f),
            nativeCompactPlateColor(isDark = true, artworkElevation = albumElevation))
        assertEquals(Color.Black.copy(alpha = 0.96f),
            nativeCompactPlateColor(isDark = true, artworkElevation = null))
        assertEquals(Color.White.copy(alpha = 0.96f),
            nativeCompactPlateColor(isDark = false, artworkElevation = null))
    }

    @Test fun compactPlateVariantHasTheSameNarrowRoundnessAsTheHeldContextMenu() {
        assertEquals(16, nativeCompactMenuCornerRadiusDp(homePlateStyle = true))
        assertEquals(18, nativeCompactMenuCornerRadiusDp(homePlateStyle = false))
    }
}
