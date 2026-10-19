package com.music.bitchord.playback

import com.music.bitchord.observability.SessionTraceReferenceEncoder
import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TracePointKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackE2ETracingTest {
    @Test
    fun `accepted play correlates Media3 ready and audible end without a raw item id`() {
        val points = mutableListOf<StructuredTracePoint>()
        val observer = PlaybackE2ETraceObserver(tracer(points))
        observer.onPlayAccepted("private-media-id")
        observer.onReady("private-media-id")
        observer.onAudible("private-media-id")
        observer.onAudible("private-media-id")

        assertEquals(listOf(TracePointKind.TRACE_START, TracePointKind.EVENT, TracePointKind.EVENT, TracePointKind.EVENT, TracePointKind.TRACE_END), points.map { it.kind })
        assertEquals(listOf("command_accepted", "media3_ready", "audible"), points.filter { it.kind == TracePointKind.EVENT }.map { it.stage })
        assertEquals(TraceOutcome.SUCCEEDED, points.last().outcome)
        assertTrue(points.map { it.traceId }.distinct().size == 1)
        assertFalse(points.toString().contains("private-media-id"))
        assertTrue(points[1].attributes.getValue("itemRef").startsWith("ref_"))
    }

    @Test
    fun `paused superseded and failed requests finish once without matching another item`() {
        val points = mutableListOf<StructuredTracePoint>()
        var seq = 0
        val observer = PlaybackE2ETraceObserver(tracer(points) { "p-${++seq}" })
        observer.onPlayAccepted("a")
        observer.onMediaChanged("b")
        observer.onAudible("b")
        observer.onPlayAccepted("c")
        observer.onError()
        observer.onPaused()

        assertEquals(listOf(TraceOutcome.CANCELLED, TraceOutcome.FAILED), points.filter { it.kind == TracePointKind.TRACE_END }.map { it.outcome })
    }

    @Test
    fun `bounded timeout closes unresolved play and ignores stale callbacks`() {
        val points = mutableListOf<StructuredTracePoint>()
        val scheduled = mutableListOf<() -> Unit>()
        var cancelled = 0
        val observer = PlaybackE2ETraceObserver(
            tracer = tracer(points),
            scheduleTimeout = { _, callback ->
                scheduled += callback
                { cancelled++ }
            },
        )
        observer.onPlayAccepted("a")
        observer.onPlayAccepted("b")
        scheduled[0]()
        assertEquals(0, points.count { it.kind == TracePointKind.TRACE_END && it.outcome == TraceOutcome.DEGRADED })
        scheduled[1]()
        observer.onReady("b")
        assertEquals(listOf(TraceOutcome.CANCELLED, TraceOutcome.DEGRADED), points.filter { it.kind == TracePointKind.TRACE_END }.map { it.outcome })
        assertEquals(2, cancelled)
    }

    private fun tracer(
        points: MutableList<StructuredTracePoint>,
        ids: () -> String = { "play-1" },
    ) = StructuredTracer(
        sink = StructuredTraceSink(points::add),
        traceIdFactory = ids,
        referenceEncoder = SessionTraceReferenceEncoder("session-private"),
    )
}
