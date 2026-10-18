package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceWindowTest {

    @Test
    fun `context change emits the previous non empty window and resets samples`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val window = RuntimePerformanceWindow(
            initialContext = "home_idle",
            onSnapshot = emitted::add,
        )

        window.recordFrame(10L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        window.recordFrame(40L * NANOS_PER_MS, 16L * NANOS_PER_MS)

        window.setContext("now_playing")

        assertEquals(1, emitted.size)
        assertEquals("home_idle", emitted.single().context)
        assertEquals(2L, emitted.single().totalFrames)
        assertEquals(1L, emitted.single().overBudgetFrames)

        window.recordFrame(8L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        window.flush()

        assertEquals(2, emitted.size)
        assertEquals("now_playing", emitted.last().context)
        assertEquals(1L, emitted.last().totalFrames)
        assertEquals(8L, emitted.last().maxMs)
    }

    @Test
    fun `setting the same context does not split the measurement window`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val window = RuntimePerformanceWindow("home_scroll", emitted::add)

        window.recordFrame(12L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        window.setContext("home_scroll")
        window.recordFrame(18L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        window.flush()

        assertEquals(1, emitted.size)
        assertEquals(2L, emitted.single().totalFrames)
    }

    @Test
    fun `empty windows never emit diagnostic noise`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val window = RuntimePerformanceWindow("home_idle", emitted::add)

        window.flush()
        window.setContext("library")
        window.setContext("now_playing")
        window.flush()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `flush starts a fresh window without losing the current context`() {
        val emitted = mutableListOf<RuntimePerformanceSnapshot>()
        val window = RuntimePerformanceWindow("now_playing", emitted::add)

        window.recordFrame(300L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        window.flush()
        window.recordFrame(9L * NANOS_PER_MS, 16L * NANOS_PER_MS)
        window.flush()

        assertEquals(2, emitted.size)
        assertEquals("now_playing", emitted[0].context)
        assertEquals("now_playing", emitted[1].context)
        assertEquals(1L, emitted[0].over250Ms)
        assertEquals(0L, emitted[1].over250Ms)
        assertEquals(9L, emitted[1].maxMs)
    }

    @Test
    fun `diagnostic metadata exposes tails percentiles and budget misses with bounded keys`() {
        val snapshot = RuntimePerformanceSnapshot(
            context = "now_playing",
            totalFrames = 200,
            overBudgetFrames = 50,
            p50Ms = 9,
            p90Ms = 18,
            p95Ms = 25,
            p99Ms = 120,
            maxMs = 640,
            over33Ms = 20,
            over50Ms = 12,
            over100Ms = 5,
            over250Ms = 2,
            over500Ms = 1,
        )

        val metadata = snapshot.toDiagnosticMetadata()

        assertEquals("now_playing", metadata["context"])
        assertEquals("200", metadata["frames"])
        assertEquals("50", metadata["overBudget"])
        assertEquals("25.00", metadata["overBudgetPercent"])
        assertEquals("9", metadata["p50Ms"])
        assertEquals("18", metadata["p90Ms"])
        assertEquals("25", metadata["p95Ms"])
        assertEquals("120", metadata["p99Ms"])
        assertEquals("640", metadata["maxMs"])
        assertEquals("20", metadata["over33Ms"])
        assertEquals("12", metadata["over50Ms"])
        assertEquals("5", metadata["over100Ms"])
        assertEquals("2", metadata["over250Ms"])
        assertEquals("1", metadata["over500Ms"])
        assertTrue(metadata.size <= 14)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
