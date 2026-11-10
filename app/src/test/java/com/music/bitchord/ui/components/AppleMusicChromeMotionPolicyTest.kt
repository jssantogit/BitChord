package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun reducedMotionKeepsNavigationIconAtUnitScaleWithoutDisablingTabSelection() {
        for (selected in listOf(false, true)) {
            for (pressed in listOf(false, true)) {
                assertEquals(
                    1f,
                    appleMusicTabScaleTarget(
                        enabled = true, selected = selected, pressed = pressed, reducedMotion = true,
                    ),
                    0.0001f,
                )
            }
        }
        assertEquals(
            1f,
            appleMusicTabScaleTarget(
                enabled = false, selected = true, pressed = true, reducedMotion = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun loadingIndicatorRemainsVisibleButStaticWhenMotionIsReduced() {
        assertNull(miniPlayerStaticLoadingArc(reducedMotion = false))
        assertEquals(0.75f, miniPlayerStaticLoadingArc(reducedMotion = true)!!, 0.0001f)
    }

    @Test
    fun dragVisualOffsetIsBoundedIndependentOfRepeatedPointerUpdates() {
        // Offset is computed only inside graphicsLayer during the actual
        // gesture; the same 72dp threshold is used for skip decisions.
        assertEquals(0f, miniPlayerSwipeVisualOffset(0f, 72f, 12f), 0.0001f)
        assertEquals(-6f, miniPlayerSwipeVisualOffset(-36f, 72f, 12f), 0.0001f)
        assertEquals(6f, miniPlayerSwipeVisualOffset(36f, 72f, 12f), 0.0001f)
        assertEquals(12f, miniPlayerSwipeVisualOffset(500f, 72f, 12f), 0.0001f)
        assertEquals(-12f, miniPlayerSwipeVisualOffset(-500f, 72f, 12f), 0.0001f)
        assertEquals(0f, miniPlayerSwipeVisualOffset(Float.NaN, 72f, 12f), 0.0001f)
        assertEquals(0f, miniPlayerSwipeVisualOffset(100f, 0f, 12f), 0.0001f)
    }

    @Test
    fun reducedMotionDisablesMiniPullElasticDisplacementButNotGestureDispatch() {
        assertEquals(0f, miniPlayerLiftForMotion(0f, 72f, reduced = false), 0.0001f)
        assertEquals(0f, miniPlayerLiftForMotion(120f, 72f, reduced = true), 0.0001f)
        assertEquals(0f, miniPlayerLiftForMotion(Float.POSITIVE_INFINITY, 72f, reduced = true), 0.0001f)
        org.junit.Assert.assertTrue(miniPlayerLiftForMotion(120f, 72f, reduced = false) > 0f)
    }

    @Test
    fun cancelledPullNeverSpringsOldMiniplayerLiftAcrossTrackReplacement() {
        assertFalse(miniPlayerShouldAnimateLiftSettle(completedNormally = false, reducedMotion = false))
        assertFalse(miniPlayerShouldAnimateLiftSettle(completedNormally = false, reducedMotion = true))
        assertFalse(miniPlayerShouldAnimateLiftSettle(completedNormally = true, reducedMotion = true))
        assertTrue(miniPlayerShouldAnimateLiftSettle(completedNormally = true, reducedMotion = false))
    }

    @Test
    fun miniplayerPressNeverScalesWhenReducedMotionIsEnabled() {
        assertEquals(0.9f, GlobalMotionPolicy.pressedScale(pressed = true, reduced = false, activeScale = 0.9f), 0.0001f)
        assertEquals(1f, GlobalMotionPolicy.pressedScale(pressed = true, reduced = true, activeScale = 0.9f), 0.0001f)
        assertEquals(1f, GlobalMotionPolicy.pressedScale(pressed = false, reduced = false, activeScale = 0.9f), 0.0001f)
    }

    @Test
    fun miniplayerBottomScrimFollowsAppearingAndDisappearingPill() {
        assertEquals(AppleMusicChromeMotion.miniAppearMillis, miniPlayerBackdropTransitionMillis(withMiniPlayer = true, reduced = false))
        assertEquals(AppleMusicChromeMotion.miniDisappearMillis, miniPlayerBackdropTransitionMillis(withMiniPlayer = false, reduced = false))
        assertEquals(0, miniPlayerBackdropTransitionMillis(withMiniPlayer = true, reduced = true))
        assertEquals(0, miniPlayerBackdropTransitionMillis(withMiniPlayer = false, reduced = true))
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
