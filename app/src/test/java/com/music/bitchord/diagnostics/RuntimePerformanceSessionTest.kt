package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceSessionTest {

    @Test
    fun `frames outside explicit capture are ignored`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val session = RuntimePerformanceSession("home_idle", emitted::add)

        session.recordFrame(80L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        session.flush()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `capture starts in the latest context without carrying disabled samples`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val session = RuntimePerformanceSession("home_idle", emitted::add)

        session.recordFrame(200L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        session.setContext("now_playing")
        session.setCaptureActive(true)
        session.recordFrame(12L * NANOS_PER_MS, 8L * NANOS_PER_MS)
        session.flush()

        assertEquals(1, emitted.size)
        assertEquals("now_playing", emitted.single().context)
        assertEquals(1L, emitted.single().totalFrames)
        assertEquals(12L, emitted.single().maxMs)
    }

    @Test
    fun `stopping capture flushes the current window and ignores later frames`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val session = RuntimePerformanceSession("local_music", emitted::add)

        session.setCaptureActive(true)
        session.recordFrame(70L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        session.setCaptureActive(false)
        session.recordFrame(500L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        session.flush()

        assertEquals(1, emitted.size)
        assertEquals("local_music", emitted.single().context)
        assertEquals(70L, emitted.single().maxMs)
    }

    @Test
    fun `active context change emits a bounded previous window`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val session = RuntimePerformanceSession("home_idle", emitted::add)

        session.setCaptureActive(true)
        session.recordFrame(9L * NANOS_PER_MS, 8L * NANOS_PER_MS)
        session.setContext("home_scroll")
        session.recordFrame(40L * NANOS_PER_MS, 8L * NANOS_PER_MS)
        session.flush()

        assertEquals(2, emitted.size)
        assertEquals("home_idle", emitted[0].context)
        assertEquals("home_scroll", emitted[1].context)
        assertEquals(1L, emitted[0].totalFrames)
        assertEquals(1L, emitted[1].totalFrames)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
