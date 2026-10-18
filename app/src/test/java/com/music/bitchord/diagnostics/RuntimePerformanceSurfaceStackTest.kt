package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePerformanceSurfaceStackTest {

    @Test
    fun `overlay context wins while visible and latest base context is restored`() {
        val stack = RuntimePerformanceSurfaceStack("home_idle")

        assertEquals("home_idle", stack.currentContext)

        stack.showOverlay("now_playing")
        assertEquals("now_playing", stack.currentContext)

        stack.setBaseContext("home_scroll")
        assertEquals("now_playing", stack.currentContext)

        stack.hideOverlay()
        assertEquals("home_scroll", stack.currentContext)
    }

    @Test
    fun `replacing visible overlay keeps the newest overlay context`() {
        val stack = RuntimePerformanceSurfaceStack("library_idle")

        stack.showOverlay("now_playing")
        stack.showOverlay("queue")

        assertEquals("queue", stack.currentContext)
    }

    @Test
    fun `hiding an absent overlay leaves the base context unchanged`() {
        val stack = RuntimePerformanceSurfaceStack("search_idle")

        stack.hideOverlay()

        assertEquals("search_idle", stack.currentContext)
    }

    @Test
    fun `latest scoped base wins and covered owner updates restore without stealing visibility`() {
        val stack = RuntimePerformanceSurfaceStack("activity_start")
        val homeOwner = Any()
        val detailOwner = Any()

        stack.enterBaseSurface(homeOwner, "home")
        assertEquals("home", stack.currentContext)

        stack.enterBaseSurface(detailOwner, "local_music")
        assertEquals("local_music", stack.currentContext)

        stack.updateBaseSurface(homeOwner, "home_scroll")
        assertEquals("local_music", stack.currentContext)

        stack.leaveBaseSurface(detailOwner)
        assertEquals("home_scroll", stack.currentContext)
    }

    @Test
    fun `leaving covered scoped base keeps the visible scoped base`() {
        val stack = RuntimePerformanceSurfaceStack("activity_start")
        val homeOwner = Any()
        val detailOwner = Any()

        stack.enterBaseSurface(homeOwner, "home")
        stack.enterBaseSurface(detailOwner, "other")
        stack.leaveBaseSurface(homeOwner)

        assertEquals("other", stack.currentContext)
    }
}
