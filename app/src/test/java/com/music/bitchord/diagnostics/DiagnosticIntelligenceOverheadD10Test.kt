package com.music.bitchord.diagnostics

import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only export-time overhead samples. These are not Android/CPU/ANR
 * measurements; hardware figures must not be inferred from these numbers.
 */
class DiagnosticIntelligenceOverheadD10Test {
    private fun event(i: Int, verbose: Boolean) = DiagnosticEvent(
        epochMillis = 1_700_000_000_000L + i,
        monotonicNanos = i * 1_000L,
        sessionId = "session-safe",
        level = DiagnosticLevel.WARN,
        category = DiagnosticCategory.NETWORK,
        name = if (i % 7 == 0) "trace_span" else "failure_recovery",
        message = if (verbose) "authorization: bearer secret-credential" else null,
        threadName = "test", durationNanos = i * 100_000L,
        metadata = if (i % 7 == 0) mapOf(
            "feature" to "provider", "operation" to "artwork_fetch", "phase" to "external",
        ) else mapOf(
            "reasonCode" to "artwork_network_unavailable", "recoveryOutcome" to "fallback",
        ),
        error = null, sequence = i.toLong(),
    )

    @Test fun boundedExportOverheadByModeComparedWithV2Baseline() {
        listOf(false, true).forEach { verbose ->
            val events = (1..1000).map { event(it, verbose) }
            val baseline = mutableListOf<Long>()
            val triage = mutableListOf<Long>()
            repeat(35) { round ->
                val t1 = System.nanoTime()
                val legacy = DiagnosticReportV2.build(
                    DiagnosticReportV2Input(
                        recentEvents = events,
                        flushComplete = true,
                        rejectedWrites = 0,
                        evictedMemoryEvents = 0,
                    ), emptyList(),
                )
                val t2 = System.nanoTime()
                val incident = DiagnosticIncidentTriage.build(events)
                val performance = DiagnosticBottleneckAnalysis.summarize(events, true)
                val t3 = System.nanoTime()
                if (round >= 10) {
                    baseline.add(t2 - t1)
                    triage.add(t3 - t2)
                }
                assertEquals(1000, legacy.events.size)
                assertTrue(incident.incidents.size <= 64)
                assertTrue(performance.size <= 32)
                assertFalse(incident.toJsonl().contains("secret-credential"))
            }
            fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
            println("D10_JVM_EXPORT_ONLY mode=" + if (verbose) "VERBOSE" else "STANDARD")
            println("D10_JVM_EXPORT_ONLY baseline_v2_median_ns=" + median(baseline))
            println("D10_JVM_EXPORT_ONLY additional_triage_median_ns=" + median(triage))
        }
    }
}
