package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCompactHomePlateStyleTest {
    @Test fun theHomePlateMenuUsesLargerRowsWithoutChangingOtherPopups() {
        assertEquals(62, nativeCompactMenuRowHeightDp(homePlateStyle = true))
        assertEquals(54, nativeCompactMenuRowHeightDp(homePlateStyle = false))
    }

    @Test fun compactPlateVariantHasTheSameNarrowRoundnessAsTheHeldContextMenu() {
        assertEquals(16, nativeCompactMenuCornerRadiusDp(homePlateStyle = true))
        assertEquals(18, nativeCompactMenuCornerRadiusDp(homePlateStyle = false))
    }
}
