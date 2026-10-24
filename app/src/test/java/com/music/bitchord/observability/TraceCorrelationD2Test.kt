package com.music.bitchord.observability

import com.music.bitchord.diagnostics.StructuredTraceDiagnosticMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceCorrelationD2Test {
    @Test fun explicitParentLinksFeaturesThroughCoroutineHopWithoutGlobalState() = runBlocking {
        val points = mutableListOf<StructuredTracePoint>()
        var seq = 0
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { synchronized(points) { points += it } },
            traceIdFactory = { "internal-id-" + ++seq },
            referenceEncoder = SessionTraceReferenceEncoder("session-only-secret"),
        )
        val ui = tracer.startTrace(TraceFeature.UI, "tap_play")
        val playback = withContext(Dispatchers.Default) {
            tracer.startTrace(TraceFeature.PLAYBACK, "play_to_audible", parent = ui.context())
        }
        val queue = tracer.startTrace(TraceFeature.PLAYBACK, "queue_resolve", parent = playback.context())
        val provider = withContext(Dispatchers.IO) {
            tracer.startTrace(TraceFeature.PROVIDER, "resolve", parent = queue.context())
        }
        val network = tracer.startTrace(TraceFeature.IO, "request", parent = provider.context())
        val catalog = tracer.startTrace(TraceFeature.CATALOG, "cache_lookup", parent = provider.context())
        listOf(catalog, network, provider, queue, playback, ui).forEach {
            it.complete(TraceOutcome.SUCCEEDED)
        }
        val starts = points.filter { it.kind == TracePointKind.TRACE_START }
        assertEquals(6, starts.size)
        assertEquals(1, starts.map { it.flowId }.distinct().size)
        assertNull(starts.first().parentSpanId)
        assertEquals(ui.context().spanId, starts[1].parentSpanId)
        assertEquals(playback.context().spanId, starts[2].parentSpanId)
        assertEquals(queue.context().spanId, starts[3].parentSpanId)
        assertEquals(provider.context().spanId, starts[4].parentSpanId)
        assertEquals(provider.context().spanId, starts[5].parentSpanId)
        assertTrue(starts.all { it.flowId?.startsWith("ref_") == true })
        assertTrue(starts.none { it.flowId?.contains("internal-id") == true })
        val mapped = StructuredTraceDiagnosticMapper.map(starts[3]).metadata
        assertEquals(starts[3].flowId, mapped["flowId"])
        assertEquals(starts[3].parentSpanId, mapped["parentSpanId"])
        assertFalse(mapped.toString().contains("session-only-secret"))
    }

    @Test fun supersedingFlowIsIsolatedAndForeignSessionContextCannotBeReused() {
        val sink = StructuredTraceSink.NO_OP
        val t1 = StructuredTracer(sink = sink, referenceEncoder = SessionTraceReferenceEncoder("a"))
        val t2 = StructuredTracer(sink = sink, referenceEncoder = SessionTraceReferenceEncoder("b"))
        val before = t1.startTrace(TraceFeature.PLAYBACK, "play_to_audible")
        val after = t1.startTrace(TraceFeature.PLAYBACK, "play_to_audible")
        assertNotEquals(before.context().flowId, after.context().flowId)
        assertNotEquals(before.context().spanId, after.context().spanId)
        var rejected = false
        try {
            t2.startTrace(TraceFeature.PROVIDER, "resolve", parent = before.context())
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("must not copy session-local trace identifiers across process sessions", rejected)
    }

    @Test fun metadataBridgeUsesWhitelistedRefsAndDoesNotPublishTrackId() {
        val tracer = StructuredTracer(referenceEncoder = SessionTraceReferenceEncoder("session"))
        val trace = tracer.startTrace(TraceFeature.UI, "open_recording")
        val attributes = TraceLegacyCorrelationBridge.attributes(trace.context(), "raw-video-id-123")
        assertEquals(trace.context().flowId, attributes["flowId"])
        assertEquals(trace.context().spanId, attributes["parentSpanId"])
        assertFalse(attributes.toString().contains("raw-video-id-123"))
        assertEquals(2, attributes.size)
    }
}
