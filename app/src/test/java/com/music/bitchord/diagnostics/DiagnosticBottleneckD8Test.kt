package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBottleneckD8Test {
    private fun event(seq: Long, millis: Long, phase: String = "external") = DiagnosticEvent(
        epochMillis = 1_700_000_000_000L + seq,
        monotonicNanos = seq * 100L,
        sessionId = "s",
        level = DiagnosticLevel.INFO,
        category = DiagnosticCategory.NETWORK,
        name = "trace_span",
        message = null, threadName = "test",
        durationNanos = millis * 1_000_000,
        metadata = mapOf("flowId" to "ref_flow", "feature" to "provider",
            "operation" to "stream_resolve", "phase" to phase),
        error = null, sequence = seq,
    )

    @Test fun p95RequiresSufficientMeasuredSamples() {
        val few = DiagnosticBottleneckAnalysis.summarize((1L..3L).map { event(it, 10) }, captureEnabled = true)
        assertEquals(DiagnosticPercentileEvidence.INSUFFICIENT, few.single().percentileEvidence)
        assertEquals(null, few.single().p95Nanos)
        val many = DiagnosticBottleneckAnalysis.summarize((1L..25L).map { event(it, it) }, captureEnabled = true)
        assertEquals(DiagnosticPercentileEvidence.MEASURED, many.single().percentileEvidence)
        assertEquals(24_000_000L, many.single().p95Nanos)
    }

    @Test fun parallelSpansNeverSumIntoInventedCriticalPath() {
        val rows = DiagnosticBottleneckAnalysis.summarize(
            listOf(event(1, 30), event(2, 40)), captureEnabled = true,
        )
        assertEquals(40_000_000L, rows.single().maxObservedNanos)
        assertFalse(rows.single().provesCriticalPath)
    }

    @Test fun disabledCaptureOrInvalidDurationsNeverCreateSamples() {
        assertTrue(DiagnosticBottleneckAnalysis.summarize(listOf(event(1, 55)), false).isEmpty())
        assertTrue(DiagnosticBottleneckAnalysis.summarize(
            listOf(event(1, -2)), true,
        ).isEmpty())
    }

    @Test fun queueRunningAndExternalWaitRemainSeparate() {
        val rows = DiagnosticBottleneckAnalysis.summarize(
            listOf(event(1, 3, "queue"), event(2, 9, "execution"), event(3, 50, "external")), true,
        )
        assertEquals(3, rows.size)
        assertEquals(setOf("queue", "execution", "external"), rows.map { it.phase }.toSet())
    }
}
