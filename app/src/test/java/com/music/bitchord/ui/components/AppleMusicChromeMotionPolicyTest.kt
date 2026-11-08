package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** State and accessibility policy for the experimental chrome animations. */
class AppleMusicChromeMotionPolicyTest {
    @Test
    fun radioNeverAnimatesAsThoughItCanBePressedOrSelected() {
        assertEquals(1f, appleMusicTabScaleTarget(enabled = false, selected = false, pressed = true), 0.0001f)
        assertEquals(1f, appleMusicTabScaleTarget(enabled = false, selected = true, pressed = true), 0.0001f)
    }

    @Test
    fun enabledTabsReactToSelectionAndPressAndReturnToRest() {
        assertEquals(1f, appleMusicTabScaleTarget(enabled = true, selected = false, pressed = false), 0.0001f)
        assertEquals(1.04f, appleMusicTabScaleTarget(enabled = true, selected = true, pressed = false), 0.0001f)
        assertEquals(0.91f, appleMusicTabScaleTarget(enabled = true, selected = true, pressed = true), 0.0001f)
    }

    @Test
    fun reduceAnimationSwitchesEveryTimedMotionToInstantState() {
        val specs = listOf(
            AppleMusicChromeMotion.tabTintMillis,
            AppleMusicChromeMotion.trackCrossfadeMillis,
            AppleMusicChromeMotion.transportCrossfadeMillis,
            AppleMusicChromeMotion.miniAppearMillis,
            AppleMusicChromeMotion.miniDisappearMillis,
        )
        for (millis in specs) {
            assertEquals(0, AppleMusicChromeMotion.duration(millis, reduced = true))
            assertEquals(millis, AppleMusicChromeMotion.duration(millis, reduced = false))
        }
    }
}
