package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePerformanceContextTest {

    @Test
    fun `local music root has its own context`() {
        assertEquals(
            "local_music",
            runtimePerformanceContext(
                isHome = false,
                homeScrollInProgress = false,
                isLocalMusicRoot = true,
            ),
        )
    }

    @Test
    fun `home scrolling uses scrolling context`() {
        assertEquals(
            "home_scroll",
            runtimePerformanceContext(
                isHome = true,
                homeScrollInProgress = true,
                isLocalMusicRoot = false,
            ),
        )
    }

    @Test
    fun `home at rest uses home context`() {
        assertEquals(
            "home",
            runtimePerformanceContext(
                isHome = true,
                homeScrollInProgress = false,
                isLocalMusicRoot = false,
            ),
        )
    }

    @Test
    fun `other surfaces use neutral context`() {
        assertEquals(
            "other",
            runtimePerformanceContext(
                isHome = false,
                homeScrollInProgress = false,
                isLocalMusicRoot = false,
            ),
        )
    }
}
