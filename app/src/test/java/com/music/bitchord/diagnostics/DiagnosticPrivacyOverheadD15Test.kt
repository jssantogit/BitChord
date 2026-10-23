package com.music.bitchord.diagnostics

import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceMode
import com.music.bitchord.observability.TraceOutcome
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D15 fixture-only comparative cost. JVM nano timings never establish Android
 * hardware cost, frame time, ANR freedom, or a performance improvement.
 */
class DiagnosticPrivacyOverheadD15Test {
    @Test fun standardVerboseAndSuppressedLogsExposeActualJvmTimingSamples() {
        val config = DiagnosticsOverheadConfig(
            repetitions = 40, warmups = 10, concurrentProducers = 1,
            captureBytesLimit = 4096, writerQueueCapacity = 512,
        )
        val measures = mutableListOf<DiagnosticsOverheadObservation>()
        val counters = mutableListOf<DiagnosticsModeCounters>()
        val cases = listOf(
            DiagnosticsCaptureMode.OFF to "search query=private artist / title=secret",
            DiagnosticsCaptureMode.STANDARD to "retry_count=3",
            DiagnosticsCaptureMode.VERBOSE to "video switch to secret artist",
        )
        for ((mode, payload) in cases) {
            var retained = 0
            repeat(config.warmups + config.repetitions) { i ->
                val started = System.nanoTime()
                val result = if (mode == DiagnosticsCaptureMode.OFF) {
                    DiagnosticSanitizer.freeText(payload)
                } else {
                    DiagnosticLegacyPrivacy.message(
                        if (mode == DiagnosticsCaptureMode.VERBOSE) "VideoSwitch" else "Network",
                        payload,
                    )
                }
                val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
                retained += result.length
                if (i >= config.warmups) {
                    measures += DiagnosticsOverheadObservation(
                        mode, DiagnosticsOverheadPhase.EVENT_FORMAT, elapsed,
                    )
                }
                if (mode != DiagnosticsCaptureMode.OFF) {
                    assertFalse(result.contains("secret artist"))
                }
            }
            assertTrue(retained > 0)
            counters += DiagnosticsModeCounters(mode, config.repetitions, 0L, 0L)
        }
        val report = DiagnosticsOverheadBaseline.summarize(config, measures, counters)
        assertTrue(report.phases.all { it.distribution.count == 40 })
        assertTrue(report.phases.all { it.distribution.p95Nanos != null })
        println("D15_JVM_SANITIZER_FIXTURE_ONLY\n" + report.toSafeText())
    }

    @Test fun structuredTracePointBudgetDoesNotGrowWithVerboseFloods() {
        for (mode in TraceMode.entries) {
            val emitted = AtomicInteger()
            val tracer = StructuredTracer(mode = mode, sink = { emitted.incrementAndGet() },
                maxIntermediatePoints = 5)
            val trace = tracer.startTrace(TraceFeature.UI, "evidence_my_ratings")
            repeat(10_000) { trace.event("ui_composed", TraceOutcome.SUCCEEDED) }
            trace.complete(TraceOutcome.SKIPPED)
            assertTrue(emitted.get() <= 7)
        }
    }
}
