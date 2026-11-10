package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalMotionPolicyTest {
    @Test fun appleTracedLoaderFadeIsBoundedToItsOwnRole() {
        // The original evidence proves this conditional loader path only.
        assertEquals(400, GlobalMotionPolicy.loaderExitMillis)
        assertEquals(400, GlobalMotionPolicy.duration(GlobalMotionPolicy.loaderExitMillis, reduced = false))
        assertEquals(0, GlobalMotionPolicy.duration(GlobalMotionPolicy.loaderExitMillis, reduced = true))
    }

    @Test fun reducedAnimationSuppressesEverySharedTimedMotion() {
        for (base in listOf(GlobalMotionPolicy.headerChromeMillis, GlobalMotionPolicy.skeletonSweepMillis, 150, 220)) {
            assertEquals(base, GlobalMotionPolicy.duration(base, reduced = false))
            assertEquals(0, GlobalMotionPolicy.duration(base, reduced = true))
        }
        assertFalse(GlobalMotionPolicy.shimmerEnabled(reduced = true))
        assertTrue(GlobalMotionPolicy.shimmerEnabled(reduced = false))
    }

    @Test fun pressScaleIsStaticWhenMotionIsReduced() {
        assertEquals(1f, GlobalMotionPolicy.pressedScale(pressed = true, reduced = true), 0.0001f)
        assertEquals(0.98f, GlobalMotionPolicy.pressedScale(pressed = true, reduced = false), 0.0001f)
        assertEquals(1f, GlobalMotionPolicy.pressedScale(pressed = false, reduced = false), 0.0001f)
    }

    @Test fun searchSelectionSharesDockTintTimingAndAlwaysObeysReduction() {
        assertEquals(180, GlobalMotionPolicy.selectionTintMillis)
        assertEquals(180, GlobalMotionPolicy.duration(GlobalMotionPolicy.selectionTintMillis, reduced = false))
        assertEquals(0, GlobalMotionPolicy.duration(GlobalMotionPolicy.selectionTintMillis, reduced = true))
    }

    @Test fun refreshSpinnerOnlyRunsWhenRefreshingAndAnimationAllowed() {
        assertTrue(GlobalMotionPolicy.indeterminateRefreshEnabled(refreshing = true, reduced = false))
        assertFalse(GlobalMotionPolicy.indeterminateRefreshEnabled(refreshing = true, reduced = true))
        assertFalse(GlobalMotionPolicy.indeterminateRefreshEnabled(refreshing = false, reduced = false))
        assertFalse(GlobalMotionPolicy.indeterminateRefreshEnabled(refreshing = false, reduced = true))
    }

    @Test fun refreshPuckIsMountedDuringExitOnlyForACompletedRealRefresh() {
        assertTrue(GlobalMotionPolicy.keepRefreshPuckVisible(
            refreshing = true, pulling = false, completedRefreshExit = false, reduced = false,
        ))
        assertTrue(GlobalMotionPolicy.keepRefreshPuckVisible(
            refreshing = false, pulling = true, completedRefreshExit = false, reduced = false,
        ))
        assertTrue(GlobalMotionPolicy.keepRefreshPuckVisible(
            refreshing = false, pulling = false, completedRefreshExit = true, reduced = false,
        ))
        assertFalse(GlobalMotionPolicy.keepRefreshPuckVisible(
            refreshing = false, pulling = false, completedRefreshExit = true, reduced = true,
        ))
        assertFalse(GlobalMotionPolicy.keepRefreshPuckVisible(
            refreshing = false, pulling = false, completedRefreshExit = false, reduced = false,
        ))
        assertEquals(400, GlobalMotionPolicy.duration(GlobalMotionPolicy.loaderExitMillis, false))
        assertEquals(0, GlobalMotionPolicy.duration(GlobalMotionPolicy.loaderExitMillis, true))
    }

    @Test fun invalidDurationIsRejectedRatherThanSilentlyAnimatingBackwards() {
        assertThrows(IllegalArgumentException::class.java) {
            GlobalMotionPolicy.duration(-1, reduced = false)
        }
    }
}
