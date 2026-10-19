package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsOverheadBaselineTest {
    private val config = DiagnosticsOverheadConfig(
        repetitions = 20,
        warmups = 5,
        concurrentProducers = 1,
        captureBytesLimit = 1_024,
        writerQueueCapacity = 1,
    )

    @Test
    fun identicalObservationsGenerateDeterministicPrivacySafePairedBaseline() {
        val rawSecret = "https://private.invalid/stream?token=do-not-export"
        val rawId = "dd51b6f2-2a2a-4f7b-aa8f-a8aa0136a70e"
        val observations = DiagnosticsCaptureMode.entries.flatMap { mode ->
            (1..20).map { nanos ->
                DiagnosticsOverheadObservation(
                    mode = mode,
                    phase = DiagnosticsOverheadPhase.EVENT_FORMAT,
                    durationNanos = nanos.toLong() * 1_000_000L,
                )
            }
        }
        val reports = listOf(
            DiagnosticsModeCounters(DiagnosticsCaptureMode.OFF, accepted = 0, dropped = 0, persistedBytes = 0),
            DiagnosticsModeCounters(DiagnosticsCaptureMode.STANDARD, accepted = 20, dropped = 0, persistedBytes = 600),
            DiagnosticsModeCounters(DiagnosticsCaptureMode.VERBOSE, accepted = 18, dropped = 2, persistedBytes = 950),
        )
        val first = DiagnosticsOverheadBaseline.summarize(config, observations, reports)
        val second = DiagnosticsOverheadBaseline.summarize(config, observations, reports)

        assertEquals(first.toSafeText(), second.toSafeText())
        assertEquals(3, first.phases.size)
        assertEquals(10_000_000L, first.phases.first().distribution.p50Nanos)
        assertEquals(19_000_000L, first.phases.first().distribution.p95Nanos)
        assertTrue(first.toSafeText().contains("writer_dropped=2"))
        assertFalse(first.toSafeText().contains(rawSecret))
        assertFalse(first.toSafeText().contains(rawId))
        assertFalse(first.toSafeText().contains("token"))
        assertFalse(first.toSafeText().contains("ref_"))
    }

    @Test
    fun underSampledOrInvalidTimingCannotReportP95OrFabricatedSpeedup() {
        val report = DiagnosticsOverheadBaseline.summarize(
            config,
            listOf(
                DiagnosticsOverheadObservation(DiagnosticsCaptureMode.OFF, DiagnosticsOverheadPhase.EVENT_FORMAT, -10L),
                DiagnosticsOverheadObservation(DiagnosticsCaptureMode.OFF, DiagnosticsOverheadPhase.EVENT_FORMAT, 12L),
                DiagnosticsOverheadObservation(DiagnosticsCaptureMode.OFF, DiagnosticsOverheadPhase.EVENT_FORMAT, 20L),
            ),
            emptyList(),
        )
        val distribution = report.phases.single().distribution
        assertEquals(2, distribution.count)
        assertNull(distribution.p95Nanos)
        assertTrue(report.toSafeText().contains("insufficient_samples"))
        assertFalse(report.toSafeText().contains("improvement"))
    }

    @Test
    fun captureModeTaxonomyDoesNotConfuseNoCaptureWithTracingDisabled() {
        assertEquals("capture_off", DiagnosticsCaptureMode.OFF.wireName)
        assertEquals("capture_standard", DiagnosticsCaptureMode.STANDARD.wireName)
        assertEquals("capture_verbose", DiagnosticsCaptureMode.VERBOSE.wireName)
        assertEquals(3, DiagnosticsCaptureMode.entries.size)
    }
}
