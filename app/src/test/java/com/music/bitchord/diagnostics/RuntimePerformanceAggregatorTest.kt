package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceAggregatorTest {

    @Test
    fun `snapshot reports deterministic percentiles without retaining every frame`() {
        val aggregator = RuntimePerformanceAggregator()
        val budgetNanos = 16_666_667L

        for (durationMs in 1L..100L) {
            aggregator.recordFrame(durationNanos = durationMs * NANOS_PER_MS, frameBudgetNanos = budgetNanos)
        }

        val snapshot = aggregator.snapshot(context = "now_playing")

        assertEquals("now_playing", snapshot.context)
        assertEquals(100L, snapshot.totalFrames)
        assertEquals(84L, snapshot.overBudgetFrames)
        assertEquals(50L, snapshot.p50Ms)
        assertEquals(90L, snapshot.p90Ms)
        assertEquals(95L, snapshot.p95Ms)
        assertEquals(99L, snapshot.p99Ms)
        assertEquals(100L, snapshot.maxMs)
        assertTrue(aggregator.storageBucketCount <= 2_002)
    }

    @Test
    fun `over budget uses the supplied frame budget instead of assuming sixty hertz`() {
        val fastDisplay = RuntimePerformanceAggregator()
        fastDisplay.recordFrame(durationNanos = 12L * NANOS_PER_MS, frameBudgetNanos = 10L * NANOS_PER_MS)

        val slowDisplay = RuntimePerformanceAggregator()
        slowDisplay.recordFrame(durationNanos = 12L * NANOS_PER_MS, frameBudgetNanos = 16L * NANOS_PER_MS)

        assertEquals(1L, fastDisplay.snapshot("home").overBudgetFrames)
        assertEquals(0L, slowDisplay.snapshot("home").overBudgetFrames)
    }

    @Test
    fun `long frame buckets preserve the tail that causes perceptible freezes`() {
        val aggregator = RuntimePerformanceAggregator()
        listOf(33L, 34L, 50L, 51L, 100L, 101L, 250L, 251L, 500L, 501L).forEach { durationMs ->
            aggregator.recordFrame(
                durationNanos = durationMs * NANOS_PER_MS,
                frameBudgetNanos = 16L * NANOS_PER_MS,
            )
        }

        val snapshot = aggregator.snapshot("local_music")

        assertEquals(9L, snapshot.over33Ms)
        assertEquals(7L, snapshot.over50Ms)
        assertEquals(5L, snapshot.over100Ms)
        assertEquals(3L, snapshot.over250Ms)
        assertEquals(1L, snapshot.over500Ms)
        assertEquals(501L, snapshot.maxMs)
    }

    @Test
    fun `histogram storage stays bounded regardless of frame count`() {
        val aggregator = RuntimePerformanceAggregator(maxTrackedDurationMs = 2_000)

        repeat(100_000) { index ->
            aggregator.recordFrame(
                durationNanos = ((index % 5_000) + 1L) * NANOS_PER_MS,
                frameBudgetNanos = 16L * NANOS_PER_MS,
            )
        }

        assertEquals(100_000L, aggregator.snapshot("home_scroll").totalFrames)
        assertEquals(2_002, aggregator.storageBucketCount)
    }

    @Test
    fun `reset starts a fresh measurement window`() {
        val aggregator = RuntimePerformanceAggregator()
        aggregator.recordFrame(400L * NANOS_PER_MS, 16L * NANOS_PER_MS)

        aggregator.reset()
        aggregator.recordFrame(8L * NANOS_PER_MS, 16L * NANOS_PER_MS)

        val snapshot = aggregator.snapshot("home_idle")
        assertEquals(1L, snapshot.totalFrames)
        assertEquals(0L, snapshot.overBudgetFrames)
        assertEquals(8L, snapshot.maxMs)
        assertEquals(0L, snapshot.over250Ms)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
