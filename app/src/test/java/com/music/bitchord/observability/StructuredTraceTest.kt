package com.music.bitchord.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredTraceTest {
    @Test
    fun `trace keeps one id across async-safe checkpoints and filters verbose in standard mode`() {
        val sink = RecordingStructuredTraceSink()
        var now = 100L
        val tracer = StructuredTracer(
            mode = TraceMode.STANDARD,
            sink = sink,
            traceIdFactory = { "trace-1" },
            monotonicNanos = { now },
            referenceEncoder = SessionTraceReferenceEncoder(sessionSalt = "session-a"),
        )

        val trace = tracer.startTrace(
            feature = TraceFeature.RECOMMENDATION,
            operation = "library_rank",
            attributes = mapOf("mode" to "library"),
        )
        trace.event(
            stage = "candidate_generation",
            outcome = TraceOutcome.SUCCEEDED,
            attributes = mapOf("candidateCount" to "42"),
        )
        trace.event(
            stage = "candidate_detail",
            outcome = TraceOutcome.SUCCEEDED,
            verbosity = TraceVerbosity.VERBOSE,
            attributes = mapOf("ignored" to "true"),
        )
        now = 250L
        trace.complete(TraceOutcome.SUCCEEDED, mapOf("outputCount" to "20"))

        assertEquals(3, sink.points.size)
        assertTrue(sink.points.all { it.traceId == "trace-1" })
        assertEquals(TracePointKind.TRACE_START, sink.points.first().kind)
        assertEquals(TracePointKind.EVENT, sink.points[1].kind)
        assertEquals(TracePointKind.TRACE_END, sink.points.last().kind)
        assertEquals(150L, sink.points.last().durationNanos)
        assertFalse(sink.points.any { it.stage == "candidate_detail" })
    }

    @Test
    fun `span records success duration and failure without swallowing exception`() {
        val sink = RecordingStructuredTraceSink()
        val ticks = ArrayDeque(listOf(10L, 25L, 40L, 50L, 100L))
        val tracer = StructuredTracer(
            mode = TraceMode.VERBOSE,
            sink = sink,
            traceIdFactory = { "trace-span" },
            monotonicNanos = { ticks.removeFirst() },
            referenceEncoder = SessionTraceReferenceEncoder(sessionSalt = "session-a"),
        )
        val trace = tracer.startTrace(TraceFeature.PROVIDER, "resolve")

        val result = trace.span("cache_lookup") { "hit" }
        assertEquals("hit", result)

        val thrown = runCatching {
            trace.span("network_fetch") { error("boom") }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        val spans = sink.points.filter { it.kind == TracePointKind.SPAN }
        assertEquals(2, spans.size)
        assertEquals(TraceOutcome.SUCCEEDED, spans[0].outcome)
        assertEquals(15L, spans[0].durationNanos)
        assertEquals(TraceOutcome.FAILED, spans[1].outcome)
        assertEquals(50L, spans[1].durationNanos)
        assertEquals("IllegalStateException", spans[1].attributes["errorType"])
    }

    @Test
    fun `session reference encoder is stable but never exposes raw canonical id`() {
        val firstSession = SessionTraceReferenceEncoder(sessionSalt = "session-a")
        val secondSession = SessionTraceReferenceEncoder(sessionSalt = "session-b")
        val raw = "recording-123-private"

        val first = firstSession.encode(raw)
        val repeated = firstSession.encode(raw)
        val second = secondSession.encode(raw)

        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertFalse(first.contains(raw))
        assertTrue(first.startsWith("ref_"))
    }

    @Test
    fun `trace bounds intermediate points while preserving start end and wrapped work`() {
        val sink = RecordingStructuredTraceSink()
        var executed = 0
        val tracer = StructuredTracer(
            mode = TraceMode.VERBOSE,
            sink = sink,
            traceIdFactory = { "trace-bounded" },
            monotonicNanos = { 100L + executed },
            referenceEncoder = SessionTraceReferenceEncoder(sessionSalt = "session-a"),
            maxIntermediatePoints = 2,
        )
        val trace = tracer.startTrace(TraceFeature.PLAYBACK, "seek")

        trace.event("accepted", TraceOutcome.SUCCEEDED)
        trace.span("dispatch") {
            executed += 1
        }
        trace.event("extra-event", TraceOutcome.SUCCEEDED)
        trace.span("extra-span") {
            executed += 1
        }
        trace.complete(TraceOutcome.SUCCEEDED)

        assertEquals(2, executed)
        assertEquals(
            listOf(
                TracePointKind.TRACE_START,
                TracePointKind.EVENT,
                TracePointKind.SPAN,
                TracePointKind.TRACE_END,
            ),
            sink.points.map { it.kind },
        )
        assertFalse(sink.points.any { it.stage == "extra-event" || it.stage == "extra-span" })
    }
}

private class RecordingStructuredTraceSink : StructuredTraceSink {
    val points = mutableListOf<StructuredTracePoint>()

    override fun record(point: StructuredTracePoint) {
        points += point
    }
}
