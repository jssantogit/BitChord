package com.music.bitchord.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedPerformanceProbeTest {

    @Test
    fun `measure records one bounded sample with deterministic duration and metadata`() {
        val times = listOf(100L, 145L).iterator()
        val samples = mutableListOf<BoundedPerformanceSample>()
        val probe = BoundedPerformanceProbe(
            nowNanos = { times.next() },
            sink = samples::add,
        )

        val result = probe.measure(
            category = DiagnosticCategory.PLAYER,
            name = "runtime_queue_rebuild",
            metadata = mapOf("operation" to "context"),
        ) {
            "result"
        }

        assertEquals("result", result)
        assertEquals(
            listOf(
                BoundedPerformanceSample(
                    category = DiagnosticCategory.PLAYER,
                    name = "runtime_queue_rebuild",
                    durationNanos = 45L,
                    metadata = mapOf("operation" to "context"),
                ),
            ),
            samples,
        )
    }

    @Test
    fun `measure records exactly once when the measured operation fails`() {
        val times = listOf(20L, 50L).iterator()
        val samples = mutableListOf<BoundedPerformanceSample>()
        val probe = BoundedPerformanceProbe(
            nowNanos = { times.next() },
            sink = samples::add,
        )

        assertThrows(IllegalStateException::class.java) {
            probe.measure(
                category = DiagnosticCategory.PLAYER,
                name = "runtime_queue_rebuild",
            ) {
                error("boom")
            }
        }

        assertEquals(1, samples.size)
        assertEquals(30L, samples.single().durationNanos)
    }

    @Test
    fun `measureSuspend records one sample around a suspend operation`() = runTest {
        val times = listOf(1_000L, 1_075L).iterator()
        val samples = mutableListOf<BoundedPerformanceSample>()
        val probe = BoundedPerformanceProbe(
            nowNanos = { times.next() },
            sink = samples::add,
        )

        val result = probe.measureSuspend(
            category = DiagnosticCategory.NETWORK,
            name = "runtime_stream_resolution",
            metadata = mapOf("provider" to "opensubsonic"),
        ) {
            7
        }

        assertEquals(7, result)
        assertEquals(1, samples.size)
        assertEquals(DiagnosticCategory.NETWORK, samples.single().category)
        assertEquals("runtime_stream_resolution", samples.single().name)
        assertEquals(75L, samples.single().durationNanos)
        assertEquals(mapOf("provider" to "opensubsonic"), samples.single().metadata)
    }
}
