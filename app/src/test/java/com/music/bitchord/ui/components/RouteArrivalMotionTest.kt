package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteArrivalMotionTest {
    @Test fun tabsOnlyAnimateBetweenTwoRootTabsRatherThanThroughDetails() {
        assertTrue(shouldAnimateRouteArrival("tab:0", "tab:2", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("tab:0", "tab:0", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("tab:2", "settings", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("settings", "tab:2", reduced = false, rootTabPrefix = "tab:"))
        assertFalse(shouldAnimateRouteArrival("tab:0", "tab:2", reduced = true, rootTabPrefix = "tab:"))
    }

    @Test fun settingsAndPlaylistFormTransitionsAcceptOnlyRealPageChanges() {
        assertTrue(shouldAnimateRouteArrival("settings:home", "settings:appearance", reduced = false))
        assertTrue(shouldAnimateRouteArrival("settings:appearance", "settings:home", reduced = false))
        assertTrue(shouldAnimateRouteArrival("picker", "create", reduced = false))
        assertTrue(shouldAnimateRouteArrival("wizard:IDENTITY", "wizard:SONGS", reduced = false))
        assertTrue(shouldAnimateRouteArrival("wizard:REVIEW", "wizard:SONGS", reduced = false))
        assertTrue(shouldAnimateRouteArrival("library:ALBUMS:shelf", "library:ALBUMS:album-id", reduced = false))
        assertTrue(shouldAnimateRouteArrival("library:ALBUMS:album-id", "library:ALBUMS:shelf", reduced = false))
        assertFalse(shouldAnimateRouteArrival("library:ARTISTS:shelf", "library:ARTISTS:shelf", reduced = false))
        assertFalse(shouldAnimateRouteArrival("create", "create", reduced = false))
        assertFalse(shouldAnimateRouteArrival("picker", "create", reduced = true))
    }

    @Test fun frostedFilterMenuOpensFromCornerWithoutChangingMeasuredSurface() {
        val first = frostedMenuEntranceFrame(progress = 0f, reducedMotion = false)
        assertEquals(0.3f, first.alpha, 0.0001f)
        assertEquals(0.94f, first.scale, 0.0001f)
        val mid = frostedMenuEntranceFrame(progress = 0.5f, reducedMotion = false)
        assertEquals(0.65f, mid.alpha, 0.0001f)
        assertEquals(0.97f, mid.scale, 0.0001f)
        val settled = frostedMenuEntranceFrame(progress = 1f, reducedMotion = false)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(1f, settled.scale, 0.0001f)
        assertEquals(settled, frostedMenuEntranceFrame(0f, reducedMotion = true))
        assertEquals(settled, frostedMenuEntranceFrame(Float.NaN, reducedMotion = false))
    }

    @Test fun rootTabsKeepTheirContentStationaryInEitherDirection() {
        val initial = rootTabArrivalFrame(0f)
        assertEquals(0f, initial.translationDp, 0.0001f)
        assertEquals(0.975f, initial.alpha, 0.0001f)
        val middle = rootTabArrivalFrame(0.5f)
        assertEquals(0f, middle.translationDp, 0.0001f)
        assertTrue(middle.alpha > initial.alpha)
        val settled = rootTabArrivalFrame(1f)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(0f, settled.translationDp, 0.0001f)
        assertEquals(settled, rootTabArrivalFrame(Float.NaN))
        assertEquals(settled, rootTabArrivalFrame(Float.POSITIVE_INFINITY))
    }

    @Test fun rootTabContentSwitchHasNoImplicitSpringSizeChange() {
        val transform = rootTabContentTransform()
        org.junit.Assert.assertNull(transform.sizeTransform)
        assertEquals(
            androidx.compose.animation.EnterTransition.None,
            transform.targetContentEnter,
        )
        assertEquals(
            androidx.compose.animation.ExitTransition.None,
            transform.initialContentExit,
        )
    }

    @Test fun incomingPageUsesBoundedSubtleOffsetWithoutBlackCrossfade() {
        val entry = routeArrivalFrame(progress = 0f, forward = true)
        assertEquals(0.96f, entry.alpha, 0.0001f)
        assertEquals(12f, entry.translationDp, 0.0001f)
        val back = routeArrivalFrame(progress = 0f, forward = false)
        assertEquals(-12f, back.translationDp, 0.0001f)
        val halfway = routeArrivalFrame(progress = 0.5f, forward = true)
        assertEquals(0.98f, halfway.alpha, 0.0001f)
        assertEquals(6f, halfway.translationDp, 0.0001f)
        val settled = routeArrivalFrame(progress = 1f, forward = true)
        assertEquals(1f, settled.alpha, 0.0001f)
        assertEquals(0f, settled.translationDp, 0.0001f)
        assertEquals(settled, routeArrivalFrame(Float.NaN, forward = false))
    }
}
