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
        assertFalse(shouldAnimateRouteArrival("create", "create", reduced = false))
        assertFalse(shouldAnimateRouteArrival("picker", "create", reduced = true))
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
