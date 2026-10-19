package com.music.bitchord.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceBaselineTest {
    private val configuration = RuntimeBaselineConfig(
        scenario = RuntimeBaselineScenario.PROVIDER_COLD,
        repetitions = 20,
        warmupRuns = 0,
        concurrency = 1,
        traceMode = TraceMode.STANDARD,
    )

    @Test
    fun `identical trace input produces comparable nearest rank percentiles by phase`() {
        fun capture(): List<StructuredTracePoint> {
            val points = mutableListOf<StructuredTracePoint>()
            var now = 1_000_000L
            var nextId = 0
            val tracer = StructuredTracer(
                sink = StructuredTraceSink { points += it },
                traceIdFactory = { "private-${++nextId}" },
                monotonicNanos = { now },
                referenceEncoder = SessionTraceReferenceEncoder("private-session"),
            )
            repeat(20) { index ->
                val trace = tracer.startTrace(TraceFeature.PROVIDER, "opensubsonic_runtime_resolve")
                trace.span("access_lookup") { now += (index + 1) * 1_000_000L }
                trace.event("identity_verified", TraceOutcome.SUCCEEDED)
                trace.complete(TraceOutcome.SUCCEEDED)
            }
            return points
        }

        val report = RuntimePerformanceBaseline.summarize(configuration, capture())
        val phase = report.phases.single { it.operation == "opensubsonic_runtime_resolve" && it.stage == "access_lookup" }
        assertEquals(20, report.correlatedTraces)
        assertEquals(0, report.incompleteTraces)
        assertEquals(20, phase.distribution.count)
        assertEquals(10_000_000L, phase.distribution.p50Nanos)
        assertEquals(19_000_000L, phase.distribution.p95Nanos)
        assertEquals(report.toSafeText(), RuntimePerformanceBaseline.summarize(configuration, capture()).toSafeText())
    }

    @Test
    fun `report excludes trace ids canonical identifiers URI and arbitrary attributes`() {
        val points = mutableListOf<StructuredTracePoint>()
        val raw = "https://private.example/stream?apiKey=secret"
        val uuid = "8c7abbec-d18e-43ac-aa89-0f54c2c7f6d4"
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { points += it },
            traceIdFactory = { uuid },
            monotonicNanos = { 100_000_000L },
            referenceEncoder = SessionTraceReferenceEncoder("private-session"),
        )
        val valid = tracer.startTrace(
            TraceFeature.PLAYBACK,
            "play_to_audible",
            attributes = mapOf("url" to raw, "trackId" to uuid),
        )
        valid.complete(TraceOutcome.SUCCEEDED, attributes = mapOf("credential" to "secret"))
        tracer.startTrace(TraceFeature.PLAYBACK, "play_to_audible") // unfinished
        val unknown = tracer.startTrace(TraceFeature.IO, "https://private.example/secret")
        unknown.complete(TraceOutcome.FAILED)

        val report = RuntimePerformanceBaseline.summarize(configuration, points)
        val exported = report.toSafeText()
        assertEquals(1, report.correlatedTraces)
        assertEquals(1, report.incompleteTraces)
        listOf(raw, uuid, "secret", "private.example", "trackId", "credential", "ref_").forEach {
            assertFalse("Leaked: $it", exported.contains(it))
        }
        assertTrue(exported.contains("play_to_audible"))
        assertFalse(exported.contains("https:"))
    }

    @Test
    fun `stalls are recovery-only observations and small samples never claim p95`() {
        val report = RuntimePerformanceBaseline.summarize(
            configuration.copy(scenario = RuntimeBaselineScenario.STALL_RECOVERY),
            emptyList(),
            recoveredStallsNanos = listOf(249_000_000L, 250_000_000L, 650_000_000L),
        )
        val stalled = report.phases.single { it.operation == "main_thread_stall" }
        assertEquals(2, stalled.distribution.count)
        assertEquals(250_000_000L, stalled.distribution.minNanos)
        assertEquals(650_000_000L, stalled.distribution.maxNanos)
        assertNotNull(stalled.distribution.p50Nanos)
        assertEquals(null, stalled.distribution.p95Nanos)
    }

    @Test
    fun `real monotonic spans have positive observed durations without performance assertions`() {
        val points = mutableListOf<StructuredTracePoint>()
        var nextId = 0
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { points += it },
            traceIdFactory = { "run-${++nextId}" },
        )
        repeat(20) {
            val trace = tracer.startTrace(TraceFeature.RECOMMENDATION, "library_rank")
            trace.span("personal_rank") { Thread.sleep(1) }
            trace.complete(TraceOutcome.SUCCEEDED)
        }
        val report = RuntimePerformanceBaseline.summarize(
            configuration.copy(scenario = RuntimeBaselineScenario.RECOMMENDATION_LIBRARY),
            points,
        )
        val stage = report.phases.single { it.operation == "library_rank" && it.stage == "personal_rank" }
        assertTrue(stage.distribution.minNanos > 0)
        assertNotNull(stage.distribution.p95Nanos)
        assertTrue(report.toSafeText().contains("repetition_count=20"))
        // CI log records an actual JVM observation; never interpret this as an Android device result.
        println("O1_JVM_OBSERVATION\n${report.toSafeText()}")
    }
}
