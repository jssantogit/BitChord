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
}
