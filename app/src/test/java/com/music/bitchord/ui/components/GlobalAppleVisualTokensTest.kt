package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shared component tokens and responsive policies. Unproven Apple measurements
 * are kept as explicit Bitchord values rather than copied across every screen.
 */
class GlobalAppleVisualTokensTest {
    @Test fun globalFloatingCircularActionsShareFortyDpSurface() {
        assertEquals(40.dp, AppleFloatingControlMetrics.libraryDiameter)
        assertEquals(40.dp, AppleFloatingControlMetrics.toolbarDiameter)
        assertEquals(24.dp, AppleFloatingControlMetrics.toolbarGlyphSize)
        assertEquals(10.dp, AppleFloatingControlMetrics.preferredGap)
        assertEquals(40.dp, AppleFloatingControlMetrics.detailDiameter)
        assertEquals(48.dp, AppleFloatingControlMetrics.minimumActionTouchTarget)
        assertEquals(50.dp, AppleFloatingControlMetrics.labeledPillHeight)
    }

    @Test fun toolbarSpacingContractsOnlyWhenAvailableWidthRequiresIt() {
        val threeButtons = listOf(48, 48, 48)
        assertEquals(10, fittingToolbarActionGapPx(threeButtons, 164, 10))
        assertEquals(4, fittingToolbarActionGapPx(threeButtons, 152, 10))
        assertEquals(0, fittingToolbarActionGapPx(threeButtons, 140, 10))
        assertEquals(10, fittingToolbarActionGapPx(threeButtons, Int.MAX_VALUE, 10))
        assertEquals(0, fittingToolbarActionGapPx(listOf(48), 140, 10))
        assertEquals(0, fittingToolbarActionGapPx(emptyList(), 140, 10))
    }

    @Test fun miniplayerTypographyFollowsDocumentedWidthQualifiedLayouts() {
        assertEquals(13.sp, appleMiniPlayerTextForWidthDp(674).titleSize)
        assertEquals(12.sp, appleMiniPlayerTextForWidthDp(674).artistSize)
        assertEquals(15.sp, appleMiniPlayerTextForWidthDp(675).titleSize)
        assertEquals(15.sp, appleMiniPlayerTextForWidthDp(675).artistSize)
        assertEquals(15.sp, appleMiniPlayerTextForWidthDp(830).titleSize)
    }
}
