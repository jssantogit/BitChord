package com.music.bitchord.acquisition.a0

import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TracePointKind
import org.junit.Assert.*
import org.junit.Test

class A0SmokeTraceTest {
    @Test fun tracesAreCorrelatedWithBoundedTypedStagesAndDuration() {
        val points = mutableListOf<StructuredTracePoint>()
        var now = 1_000_000L
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { points.add(it) },
            traceIdFactory = { "a0-test-trace" },
            monotonicNanos = { now },
        )
        val trace = A0SmokeTrace.start(tracer, monotonicNanos = { now })
        trace.note(A0SmokeTrace.Stage.SERVER_CONNECT_STARTED)
        now += 2_500_000L
        trace.note(A0SmokeTrace.Stage.SERVER_CONNECTED)
        trace.note(
            A0SmokeTrace.Stage.SERVER_OFFER_REJECTED,
            TraceOutcome.DEGRADED,
            A0SmokeTrace.Reason.WIRE_REJECTED,
        )
        trace.finish(TraceOutcome.DEGRADED, A0SmokeTrace.Reason.NO_PEER_REPLY)
        assertEquals(5, points.size)
        assertEquals(setOf("a0-test-trace"), points.map { it.traceId }.toSet())
        assertEquals(TracePointKind.TRACE_START, points.first().kind)
        assertEquals("server_offer_rejected", points[3].stage)
        assertEquals("wire_rejected", points[3].attributes["reasonCode"])
        assertEquals("2", points.last().attributes["elapsedMs"])
        assertEquals("no_peer_reply", points.last().attributes["reasonCode"])
        assertEquals(TracePointKind.TRACE_END, points.last().kind)
    }

    @Test fun allLoggedFieldsAreStrictlyAllowlistedNeverPeerData() {
        val points = mutableListOf<StructuredTracePoint>()
        val tracer = StructuredTracer(sink = StructuredTraceSink { points.add(it) })
        val trace = A0SmokeTrace.start(tracer)
        for (stage in A0SmokeTrace.Stage.values()) {
            trace.note(stage, TraceOutcome.SUCCEEDED)
        }
        trace.finish(TraceOutcome.SUCCEEDED)
        val output = points.toString()
        for (secret in listOf(
            "username", "password", "remoteFilename", "peerAddress", "token=",
            "Ringingsound.wav", "2416", "127.0.0.1"
        )) assertFalse("Unsafe field $secret", output.contains(secret, ignoreCase = true))
    }

    @Test fun networkContextOnlyExportsCoarseTransportType() {
        val points = mutableListOf<StructuredTracePoint>()
        val trace = A0SmokeTrace.start(
            StructuredTracer(sink = StructuredTraceSink { points.add(it) })
        )
        trace.networkTransport(A0SmokeTrace.Transport.CELLULAR)
        trace.finish(TraceOutcome.DEGRADED)
        val event = points.single { it.stage == "network_context" }
        assertEquals("cellular", event.attributes["transport"])
        assertFalse(event.attributes.keys.any { it.contains("ip", true) ||
            it.contains("carrier", true) || it.contains("ssid", true) })
    }

    @Test fun repeatedUnrelatedServerFramesAreAggregatedAndTerminalIsOnceOnly() {
        val points = mutableListOf<StructuredTracePoint>()
        val trace = A0SmokeTrace.start(
            StructuredTracer(sink = StructuredTraceSink { points.add(it) })
        )
        repeat(200) { trace.ignoredServerFrame() }
        trace.finish(TraceOutcome.DEGRADED, A0SmokeTrace.Reason.NO_PEER_REPLY)
        trace.note(A0SmokeTrace.Stage.SERVER_CONNECTED)
        trace.finish(TraceOutcome.FAILED)
        assertEquals(1, points.count { it.kind == TracePointKind.TRACE_END })
        assertEquals("200", points.last().attributes["ignoredServerFrames"])
        assertTrue(points.size <= 8)
    }
}
