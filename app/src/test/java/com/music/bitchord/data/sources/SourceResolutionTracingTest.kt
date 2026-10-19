package com.music.bitchord.data.sources

import com.music.bitchord.observability.SessionTraceReferenceEncoder
import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TracePointKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolutionTracingTest {
    @Test
    fun `successful source lookup correlates stages without raw source track or URL`() = runBlocking {
        val points = mutableListOf<StructuredTracePoint>()
        val tracer = StructuredTracer(
            sink = StructuredTraceSink(points::add),
            traceIdFactory = { "source-trace" },
            referenceEncoder = SessionTraceReferenceEncoder("private-test-salt"),
        )
        val run = StructuredSourceResolutionTraceReporter(tracer)
            .start("private-source-id", "private-track-id")

        val result = run.lookup { "https://secret.invalid/audio?token=secret" }
        run.complete(found = result != null)

        assertTrue(result!!.startsWith("https://secret.invalid"))
        assertEquals(listOf(TracePointKind.TRACE_START, TracePointKind.SPAN, TracePointKind.EVENT, TracePointKind.TRACE_END), points.map { it.kind })
        assertTrue(points.all { it.traceId == "source-trace" && it.feature == TraceFeature.PROVIDER })
        assertEquals(TraceOutcome.SUCCEEDED, points.last().outcome)
        assertTrue(points.first().attributes.getValue("sourceRef").startsWith("ref_"))
        assertTrue(points.first().attributes.getValue("trackRef").startsWith("ref_"))
        val serialized = points.toString()
        assertFalse(serialized.contains("private-source-id"))
        assertFalse(serialized.contains("private-track-id"))
        assertFalse(serialized.contains("secret.invalid"))
        assertFalse(serialized.contains("token=secret"))
    }

    @Test
    fun `missing source and cancellation close separate traces with typed outcomes`() = runBlocking {
        val points = mutableListOf<StructuredTracePoint>()
        var counter = 0
        val tracer = StructuredTracer(
            sink = StructuredTraceSink(points::add),
            traceIdFactory = { "source-${++counter}" },
        )
        val reporter = StructuredSourceResolutionTraceReporter(tracer)
        val missing = reporter.start("source", "track")
        missing.lookup { null as String? }
        missing.complete(found = false)
        missing.complete(found = true)
        reporter.start("source", "track").cancel()

        val ends = points.filter { it.kind == TracePointKind.TRACE_END }
        assertEquals(2, ends.size)
        assertEquals(listOf(TraceOutcome.SKIPPED, TraceOutcome.CANCELLED), ends.map { it.outcome })
    }
}
