package com.music.bitchord.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredTraceTest {
    @Test
    fun `no-op tracer is safe and never enables tracing`() {
        val trace = NoOpStructuredTracer.startTrace(
            feature = "playback",
            operation = "seek",
        )

        assertFalse(NoOpStructuredTracer.enabled)
        assertFalse(trace.enabled)

        trace.checkpoint(
            stage = "ready",
            outcome = TraceOutcome.SUCCEEDED,
        )
        trace.finish(TraceOutcome.SUCCEEDED)
    }

    @Test
    fun `bounded trace emits at most the configured checkpoint budget and finishes once`() {
        val points = mutableListOf<TracePoint>()
        val trace = BoundedTraceHandle(
            traceId = "trace-1",
            feature = "playback",
            operation = "seek",
            maxCheckpoints = 2,
            emit = points::add,
        )

        trace.checkpoint("accepted")
        trace.checkpoint("dispatch")
        trace.checkpoint("extra-must-be-dropped")
        trace.finish(TraceOutcome.SUCCEEDED, stage = "ready")
        trace.finish(TraceOutcome.FAILED, stage = "duplicate-finish")
        trace.checkpoint("after-finish")

        assertEquals(3, points.size)
        assertEquals(listOf("accepted", "dispatch", "ready"), points.map { it.stage })
        assertEquals(TracePointKind.CHECKPOINT, points[0].kind)
        assertEquals(TracePointKind.FINISH, points[2].kind)
        assertEquals(TraceOutcome.SUCCEEDED, points[2].outcome)
        assertTrue(trace.finished)
    }

    @Test
    fun `metadata and entity references are separate payloads`() {
        val points = mutableListOf<TracePoint>()
        val trace = BoundedTraceHandle(
            traceId = "trace-2",
            feature = "provider",
            operation = "stream_resolution",
            maxCheckpoints = 4,
            emit = points::add,
        )

        trace.checkpoint(
            stage = "asset_loaded",
            metadata = mapOf("provider" to "opensubsonic"),
            references = listOf(
                EntityRef(kind = "recording", value = "raw-recording-id"),
                EntityRef(kind = "media_asset", value = "raw-asset-id"),
            ),
        )

        val point = points.single()
        assertEquals(mapOf("provider" to "opensubsonic"), point.metadata)
        assertEquals(
            listOf("recording", "media_asset"),
            point.references.map { it.kind },
        )
        assertEquals(
            listOf("raw-recording-id", "raw-asset-id"),
            point.references.map { it.value },
        )
    }

    @Test
    fun `event points use the same fixed vocabulary without a trace handle`() {
        val points = mutableListOf<TracePoint>()
        val tracer = RecordingStructuredTracer(points::add)

        tracer.event(
            feature = "ui",
            operation = "open_now_playing",
            stage = "visible",
            outcome = TraceOutcome.SUCCEEDED,
            metadata = mapOf("surface" to "now_playing"),
        )

        val point = points.single()
        assertEquals(TracePointKind.EVENT, point.kind)
        assertEquals("ui", point.feature)
        assertEquals("open_now_playing", point.operation)
        assertEquals("visible", point.stage)
        assertEquals(TraceOutcome.SUCCEEDED, point.outcome)
        assertEquals(null, point.traceId)
    }
}
