package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music 7.0.0 visual smoke contract.
 *
 * 62dp navigation / 30dp tab icons / 48dp mini player are XML tokens
 * documented in am-descomp (docs/components and design/spacing.json).
 * Label and text sizes are screenshot-calibrated, not proved runtime values.
 */
class AppleMusicBottomBarVisualMetricsTest {
    @Test
    fun navigationKeepsDocumentedChromeAndEnlargedIcons() {
        assertEquals(62.dp, AppleMusicChromeMetrics.navigationHeight)
        assertEquals(30.dp, AppleMusicChromeMetrics.navigationIconSize)
        assertEquals(13.sp, AppleMusicChromeMetrics.navigationLabelSize)
    }

    @Test
    fun miniPlayerControlsGrowInsideDocumentedPill() {
        assertEquals(48.dp, AppleMusicChromeMetrics.miniPlayerHeight)
        assertEquals(34.dp, AppleMusicChromeMetrics.artworkSize)
        assertEquals(40.dp, AppleMusicChromeMetrics.controlTouchSize)
        assertEquals(32.dp, AppleMusicChromeMetrics.playGlyphSize)
        assertEquals(32.dp, AppleMusicChromeMetrics.nextGlyphSize)
        assertTrue(AppleMusicChromeMetrics.controlTouchSize <= AppleMusicChromeMetrics.miniPlayerHeight)
    }

    @Test
    fun miniPlayerTextIsLegibleWithTwoLinesInThePill() {
        assertEquals(15.sp, AppleMusicChromeMetrics.titleSize)
        assertEquals(15.sp, AppleMusicChromeMetrics.artistSize)
    }
}
