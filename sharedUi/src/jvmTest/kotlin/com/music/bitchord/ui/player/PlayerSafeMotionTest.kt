package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Documented REA motion contract: never blend two actionable capsules. */
class PlayerSafeMotionTest {
    @Test fun queuePillHasExactlyOneVisualOwnerDuringBothDirectionChanges() {
        val normal = playerQueuePillMotion(reduced = false)
        assertEquals(0, normal.exitMillis)
        assertEquals(180, normal.enterMillis)
        assertEquals(220, normal.resizeMillis)
        assertTrue(normal.slideMillis > 0)
    }

    @Test fun reducedMotionSwitchesQueueCapsulesImmediately() {
        val reduced = playerQueuePillMotion(reduced = true)
        assertEquals(0, reduced.enterMillis)
        assertEquals(0, reduced.exitMillis)
        assertEquals(0, reduced.resizeMillis)
        assertEquals(0, reduced.slideMillis)
    }

    @Test fun reduceAnimationRemovesSecondaryPanelAndDrawerMotionWithoutChangingGestureTargets() {
        assertEquals(420, playerPanelMotionMillis(420, reduced = false))
        assertEquals(200, playerPanelMotionMillis(200, reduced = false))
        assertEquals(0, playerPanelMotionMillis(420, reduced = true))
        assertEquals(0, playerPanelMotionMillis(200, reduced = true))
        assertEquals(0, playerPanelMotionMillis(260, reduced = true))
        assertEquals(0, playerPanelMotionMillis(180, reduced = true))
    }
}
