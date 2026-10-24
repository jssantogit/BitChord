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
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { points.add(it) },
            traceIdFactory = { "fixed-a0-id" }
        )
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

    @Test fun ignoredServerCodesRemainBoundedAndIncludeSafeNumericFrameTypes() {
        val points = mutableListOf<StructuredTracePoint>()
        val trace = A0SmokeTrace.start(
            StructuredTracer(sink = StructuredTraceSink { points.add(it) })
        )
        trace.ignoredServerFrame(64)
        trace.ignoredServerFrame(102)
        trace.ignoredServerFrame(Int.MAX_VALUE)
        trace.finish(TraceOutcome.DEGRADED)
        val ignored = points.filter { it.stage == "server_frame_ignored" }
        assertEquals(listOf("64", "102", "out_of_range"), ignored.map { it.attributes["serverCode"] })
        assertEquals("3", points.last().attributes["ignoredServerFrames"])
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

    @Test fun observedTransferFailureIsTypedAndCannotLeaveSuccessfulTraceEnd() {
        val points=mutableListOf<StructuredTracePoint>()
        val trace=A0SmokeTrace.start(StructuredTracer(
            sink=StructuredTraceSink {points.add(it)}
        ),observedTransfer=true)
        trace.transferPhase(A0ServerAssociatedTransfer.Phase.GATE_PASSED)
        trace.transferPhase(A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_REQUESTED)
        trace.transferFailure(
            A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_REQUESTED,
            java.io.EOFException()
        )
        trace.finish(TraceOutcome.FAILED,A0SmokeTrace.Reason.SERVER_IO_FAILURE)
        assertTrue(points.any {it.stage=="pf_peer_address_requested"})
        val error=points.single {it.stage=="pf_transfer_failure"}
        assertEquals("peer_address_requested",error.attributes["lastPhase"])
        assertEquals("eof",error.attributes["failureKind"])
        assertEquals(TraceOutcome.FAILED,points.last().outcome)
        assertFalse(points.toString().contains("remoteFilename"))
    }

    @Test fun peerAddressFailureReportsOnlyAllowlistedReasonAndBoundedFrameCount() {
        val points=mutableListOf<StructuredTracePoint>()
        val trace=A0SmokeTrace.start(StructuredTracer(
            sink=StructuredTraceSink { points.add(it) }
        ),observedTransfer=true)
        trace.transferFailure(A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_REQUESTED,
            A0ServerAssociatedTransfer.PeerAddressFailure(
                A0ServerAssociatedTransfer.AddressFailureKind.BACKLOG_LIMIT,256
            ))
        trace.finish(TraceOutcome.FAILED)
        val failure=points.single { it.stage=="pf_transfer_failure" }
        assertEquals("backlog_limit",failure.attributes["addressFailure"])
        assertEquals("256",failure.attributes["unrelatedFrames"])
        assertEquals("peer_address_requested",failure.attributes["lastPhase"])
        assertEquals("protocol_or_io",failure.attributes["failureKind"])
        assertFalse(failure.attributes.toString().contains("remotePath"))
        assertFalse(failure.attributes.toString().contains("peerUsername"))
    }

    @Test fun FControlFailuresHaveSafeSpecificCodesInsteadOfProtocolOrIo() {
        val points=mutableListOf<StructuredTracePoint>()
        val trace=A0SmokeTrace.start(StructuredTracer(
            sink=StructuredTraceSink {points.add(it)}
        ),observedTransfer=true)
        trace.transferFailure(A0ServerAssociatedTransfer.Phase.F_WAIT_STARTED,
            A0IndirectFileOfferWire.MalformedFileOffer())
        trace.transferFailure(A0ServerAssociatedTransfer.Phase.F_WAIT_STARTED,
            A0FileChannelRendezvous.TruncatedControlFrame())
        trace.transferFailure(A0ServerAssociatedTransfer.Phase.F_ESTABLISHED,
            A0ModernIncomingTransfer.FileTokenMismatch())
        trace.finish(TraceOutcome.FAILED)
        val codes=points.filter {it.stage=="pf_transfer_failure"}
            .map { it.attributes["failureKind"] }
        assertEquals(listOf(
            "invalid_f_offer","partial_f_control_frame","f_token_mismatch"
        ),codes)
        assertFalse(points.toString().contains("peerUsername"))
    }

    @Test fun physicalSavePublishedSurvives64IntermediateEventsFromSearch() {
        val points=mutableListOf<StructuredTracePoint>()
        val smoke=A0SmokeTrace.start(StructuredTracer(
            sink=StructuredTraceSink {points.add(it)},
            maxIntermediatePoints=64
        ),observedTransfer=true)
        repeat(75) {
            smoke.note(A0SmokeTrace.Stage.SERVER_OFFER_REJECTED,
                TraceOutcome.DEGRADED)
        }
        smoke.note(A0SmokeTrace.Stage.CANDIDATES_AVAILABLE)
        smoke.note(A0SmokeTrace.Stage.P_F_TRANSFER_STARTED)
        smoke.transferPhase(A0ServerAssociatedTransfer.Phase.BYTES_RECEIVED)
        smoke.note(A0SmokeTrace.Stage.LOCAL_SAVE_STAGED)
        smoke.note(A0SmokeTrace.Stage.LOCAL_SAVE_PUBLISHED)
        smoke.note(A0SmokeTrace.Stage.SCRATCH_CLEANUP)
        smoke.note(A0SmokeTrace.Stage.SOCKETS_CLOSED)
        smoke.finish(TraceOutcome.SUCCEEDED)

        val stages=points.mapNotNull {it.stage}
        for(stage in listOf("candidates_available","p_f_transfer_started",
            "pf_bytes_received","local_save_staged","local_save_published",
            "scratch_cleanup","sockets_closed")) {
            assertTrue("A5 publish evidence must survive search saturation: $stage",
                stage in stages)
        }
        val first=points.first {it.kind==TracePointKind.TRACE_START}
        val published=points.first {it.stage=="local_save_published"}
        assertEquals(first.flowId,published.flowId)
        assertNotEquals(first.traceId,published.traceId)
        assertEquals(2,points.count {it.kind==TracePointKind.TRACE_END})
    }

    @Test fun deliberateSocketShutdownDoesNotEmitSpuriousServerReadFailure() {
        val points = mutableListOf<StructuredTracePoint>()
        val trace = A0SmokeTrace.start(
            StructuredTracer(sink = StructuredTraceSink { points.add(it) })
        )
        // Closed sockets on the completed probe must not masquerade as a fresh error.
        trace.note(A0SmokeTrace.Stage.SOCKETS_CLOSED)
        trace.note(
            A0SmokeTrace.Stage.SERVER_READ_FAILURE,
            TraceOutcome.DEGRADED,
            A0SmokeTrace.Reason.SERVER_IO_FAILURE
        )
        trace.finish(TraceOutcome.DEGRADED, A0SmokeTrace.Reason.NO_PEER_REPLY)
        assertFalse(points.any { it.stage == "server_read_failure" })
        assertTrue(points.any { it.stage == "sockets_closed" })
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
        assertTrue(points.size <= 18)
    }
}
