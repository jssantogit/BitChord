package com.music.bitchord.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredTraceContractTest {
    @Test
    fun `shared tracer is no-op by default and bounded handles finish once`() {
        assertFalse(NoOpStructuredTracer.enabled)
        assertFalse(
            NoOpStructuredTracer.startTrace(
                feature = "playback",
                operation = "seek",
            ).enabled,
        )

        val points = mutableListOf<TracePoint>()
        val trace = BoundedTraceHandle(
            traceId = "trace-ci",
            feature = "playback",
            operation = "seek",
            maxCheckpoints = 2,
            emit = points::add,
        )

        trace.checkpoint("accepted")
        trace.checkpoint("dispatched")
        trace.checkpoint("must_drop")
        trace.finish(TraceOutcome.SUCCEEDED, stage = "ready")
        trace.finish(TraceOutcome.FAILED, stage = "duplicate")

        assertTrue(trace.finished)
        assertEquals(listOf("accepted", "dispatched", "ready"), points.map { it.stage })
        assertEquals(TracePointKind.FINISH, points.last().kind)
    }

    @Test
    fun `entity references stay structurally separate from metadata`() {
        val points = mutableListOf<TracePoint>()
        val trace = BoundedTraceHandle(
            traceId = "trace-ref",
            feature = "provider",
            operation = "stream_resolution",
            maxCheckpoints = 1,
            emit = points::add,
        )

        trace.checkpoint(
            stage = "asset_loaded",
            metadata = mapOf("provider" to "opensubsonic"),
            references = listOf(EntityRef("recording", "raw-recording-id")),
        )

        assertEquals("opensubsonic", points.single().metadata["provider"])
        assertEquals("recording", points.single().references.single().kind)
        assertEquals("raw-recording-id", points.single().references.single().value)
    }
}
