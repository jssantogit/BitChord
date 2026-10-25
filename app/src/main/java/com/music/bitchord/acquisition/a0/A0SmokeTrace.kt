package com.music.bitchord.acquisition.a0

import com.music.bitchord.observability.StructuredTrace
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Typed privacy-by-construction read-only A0 smoke instrumentation.
 *
 * NO caller-supplied strings, socket addresses, credentials, peer identities,
 * filenames, payloads, server tokens, exception texts, or paths are accepted.
 * All fields pass through the app's existing structured diagnostics sink.
 */
internal class A0SmokeTrace private constructor(
    private val trace: StructuredTrace,
    private val tracer: StructuredTracer,
    private val startNanos: Long,
    private val monotonicNanos: () -> Long
) {
    enum class Stage {
        NETWORK_CONTEXT, CAPTURE_READY, LOGIN_STARTED, SERVER_CONNECT_STARTED, SERVER_CONNECTED,
        LOGIN_PACKET_SENT, LOGIN_RESPONSE_FIRST_BYTE, LOGIN_RESPONSE_TIMEOUT,
        LOGIN_RESPONSE_READ_FAILURE, LOGIN_ACCEPTED, SEARCH_LISTENER_OPEN,
        SEARCH_PORT_ANNOUNCED, SEARCH_SENT, SEARCH_WAIT_STARTED,
        DIRECT_ACCEPTED, DIRECT_FRAME_RECEIVED, DIRECT_REPLY_REJECTED,
        DIRECT_REPLY_ACCEPTED, DIRECT_READ_TIMEOUT, DIRECT_IO_FAILURE,
        SERVER_FRAME_IGNORED, SERVER_OFFER_RECEIVED, SERVER_OFFER_REJECTED,
        SERVER_READ_TIMEOUT, SERVER_READ_FAILURE, INDIRECT_CONNECT_STARTED,
        INDIRECT_CONNECTED, INDIRECT_CONNECT_FAILED, PIERCE_SENT,
        INDIRECT_REPLY_ACCEPTED, INDIRECT_REPLY_REJECTED,
        INDIRECT_READ_TIMEOUT, INDIRECT_IO_FAILURE,
        NO_REPLY, NO_ELIGIBLE_CANDIDATE, SEARCH_COMPLETED, SEARCH_READERS_JOINED,
        CANDIDATES_AVAILABLE,
        MANUAL_SELECTION_CANCELLED, P_F_TRANSFER_STARTED, P_F_TRANSFER_COMPLETED,
        LOCAL_SAVE_REQUESTED, LOCAL_SAVE_STAGED, LOCAL_SAVE_PUBLISHED, LOCAL_SAVE_FAILED,
        SCRATCH_CLEANUP, STOP_REQUESTED, SOCKETS_CLOSED,
        LOGIN_DENIED, IO_FAILURE, UNEXPECTED_FAILURE
    }

    enum class Reason {
        WIRE_REJECTED, NO_PEER_REPLY, NO_ELIGIBLE_CANDIDATE,
        LOGIN_DENIED, DNS_FAILURE,
        CONNECT_FAILED, CONNECT_TIMEOUT, READ_TIMEOUT, SOCKET_CLOSED,
        SERVER_IO_FAILURE, PEER_IO_FAILURE, CANCELLED, INVALID_INPUT,
        UNKNOWN_FAILURE
    }

    enum class Transport { WIFI, CELLULAR, VPN, ETHERNET, OTHER, DISCONNECTED, UNKNOWN }

    private val complete = AtomicBoolean(false)
    private val socketsClosing = AtomicBoolean(false)
    private val ignoredServerFrames = AtomicInteger(0)
    @Volatile private var continuation: StructuredTrace? = null

    /** Protect local-save evidence when noisy search uses all 64 trace points. */
    private fun continuationTrace():StructuredTrace {
        continuation?.let { return it }
        return synchronized(this) {
            continuation ?: tracer.startTrace(
                feature=TraceFeature.PROVIDER,
                operation="a0_soulseek_file_outcome",
                attributes=mapOf("environment" to "devdebug"),
                parent=trace.context()
            ).also { continuation=it }
        }
    }

    private fun eventTrace(stage:Stage):StructuredTrace =
        if(continuation!=null || stage in setOf(
            Stage.CANDIDATES_AVAILABLE,Stage.MANUAL_SELECTION_CANCELLED,
            Stage.P_F_TRANSFER_STARTED,Stage.P_F_TRANSFER_COMPLETED,
            Stage.LOCAL_SAVE_STAGED,Stage.LOCAL_SAVE_PUBLISHED,
            Stage.LOCAL_SAVE_FAILED,Stage.SCRATCH_CLEANUP,
            Stage.SOCKETS_CLOSED,Stage.IO_FAILURE,Stage.UNEXPECTED_FAILURE,
            Stage.LOGIN_DENIED,Stage.SERVER_READ_TIMEOUT,Stage.STOP_REQUESTED
        )) continuationTrace() else trace

    fun note(
        stage: Stage,
        outcome: TraceOutcome = TraceOutcome.SUCCEEDED,
        reason: Reason? = null
    ) {
        if (complete.get()) return
        if (stage == Stage.SOCKETS_CLOSED) socketsClosing.set(true)
        if (stage == Stage.SERVER_READ_FAILURE && socketsClosing.get()) return
        val attributes = linkedMapOf("elapsedMs" to elapsedMillis().toString())
        if (reason != null) attributes["reasonCode"] = reason.name.lowercase(Locale.ROOT)
        eventTrace(stage).event(
            stage = stage.name.lowercase(Locale.ROOT),
            outcome = outcome,
            attributes = attributes
        )
    }

    /** Only known enum phases; never emit peer identity, remote path or frame data. */
    fun transferPhase(phase:A0ServerAssociatedTransfer.Phase) {
        if(complete.get())return
        continuationTrace().event(
            stage="pf_"+phase.name.lowercase(Locale.ROOT),
            outcome=TraceOutcome.SUCCEEDED,
            attributes=mapOf("elapsedMs" to elapsedMillis().toString())
        )
    }

    /** Aggregate-only observation. Never expose peer offers or their payloads. */
    fun selectionDrain(discardedFrames:Int) {
        if(complete.get())return
        continuationTrace().event(
            stage="selection_backlog_drained",
            outcome=TraceOutcome.SUCCEEDED,
            attributes=mapOf(
                "elapsedMs" to elapsedMillis().toString(),
                "discardedFrames" to discardedFrames.coerceIn(0,65_536).toString()
            )
        )
    }

    /** Failure class only, without exception message, stacktrace or socket data. */
    fun transferFailure(
        lastPhase:A0ServerAssociatedTransfer.Phase?,
        error:Throwable
    ) {
        if(complete.get())return
        val failureKind=when(error) {
            is A0IndirectFileOfferWire.MalformedFileOffer -> "invalid_f_offer"
            is A0FileChannelRendezvous.TruncatedControlFrame -> "partial_f_control_frame"
            is A0ModernIncomingTransfer.FileTokenMismatch -> "f_token_mismatch"
            is java.io.EOFException -> "eof"
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.ConnectException -> "connect"
            is java.net.SocketException -> "socket"
            is java.io.IOException -> "protocol_or_io"
            is IllegalArgumentException -> "invalid_input"
            else -> "other"
        }
        val details=linkedMapOf(
            "elapsedMs" to elapsedMillis().toString(),
            "lastPhase" to (lastPhase?.name?.lowercase(Locale.ROOT) ?: "precheck"),
            "failureKind" to failureKind
        )
        if (error is A0ServerAssociatedTransfer.PeerAddressFailure) {
            details["addressFailure"] = error.kind.name.lowercase(Locale.ROOT)
            details["unrelatedFrames"] = error.unrelatedFrames.coerceIn(0,256).toString()
        }
        continuationTrace().event(
            stage="pf_transfer_failure",
            outcome=TraceOutcome.FAILED,
            attributes=details
        )
    }

    /** Redacted filter coverage: never store query, peer or file names. */
    fun candidateScreening(report:A0ObservedCandidateHandoff.Screening) {
        if(complete.get())return
        fun n(value:Int)=value.coerceIn(0,100000).toString()
        continuationTrace().event(
            stage="candidate_screening",
            outcome=if(report.accepted>0) TraceOutcome.SUCCEEDED
                else TraceOutcome.DEGRADED,
            attributes=mapOf(
                "elapsedMs" to elapsedMillis().toString(),
                "validatedReplies" to n(report.responses),
                "announcedPublicFiles" to n(report.publicFiles),
                "announcedLockedFiles" to n(report.lockedFiles),
                "tooLarge" to n(report.tooLarge),
                "unsupportedFormat" to n(report.unsupportedFormat),
                "unsafePath" to n(report.unsafePath),
                "noSlot" to n(report.noSlot),
                "validBeforeLimit" to n(report.validBeforeLimit),
                "duplicates" to n(report.duplicates),
                "capacityDropped" to n(report.capacityDropped),
                "perPeerDropped" to n(report.perPeerDropped),
                "strongMatches" to n(report.strongMatches),
                "weakMatches" to n(report.weakMatches),
                "lowQuality" to n(report.lowQuality),
                "preflightStrong" to n(report.preflightStrong),
                "preflightReview" to n(report.preflightReview),
                "preflightUnsafe" to n(report.preflightUnsafe),
                "selectable" to n(report.accepted)
            )
        )
    }

    /** No peer/path/query data. Measures offers dropped by the bounded P pool. */
    fun peerSearchBackpressure(dropped:Int) {
        if(complete.get())return
        continuationTrace().event(
            stage="peer_search_backpressure",
            outcome=if(dropped>0)TraceOutcome.DEGRADED else TraceOutcome.SUCCEEDED,
            attributes=mapOf("droppedOffers" to dropped.coerceIn(0,100000).toString())
        )
    }

    /** Mark deliberate teardown before sockets are closed, suppressing read errors it causes. */
    fun beginSocketTeardown() {
        socketsClosing.set(true)
    }

    /** Distinguish silent login server from partially received frame, without raw bytes. */
    fun loginResponseProblem(phase: A0LoginReadProgress.Phase, timedOut: Boolean) {
        if (complete.get()) return
        trace.event(
            stage = if (timedOut) "login_response_timeout" else "login_response_read_failure",
            outcome = TraceOutcome.DEGRADED,
            attributes = mapOf(
                "elapsedMs" to elapsedMillis().toString(),
                "readPhase" to phase.name.lowercase(Locale.ROOT),
                "reasonCode" to if (timedOut) "read_timeout" else "server_io_failure"
            )
        )
    }

    fun networkTransport(transport: Transport) {
        if (complete.get()) return
        trace.event(
            stage = Stage.NETWORK_CONTEXT.name.lowercase(Locale.ROOT),
            outcome = TraceOutcome.SUCCEEDED,
            attributes = mapOf(
                "elapsedMs" to elapsedMillis().toString(),
                "transport" to transport.name.lowercase(Locale.ROOT)
            )
        )
    }

    /**
     * Codes are protocol constants only (fixed four-byte header), NEVER payloads.
     * Preserve the first 16 frames, with a hard range and overall count cap.
     */
    fun ignoredServerFrame(code: Int = -1) {
        if (complete.get()) return
        val seen = ignoredServerFrames.incrementAndGet()
        if (seen <= 16) {
            trace.event(
                stage = Stage.SERVER_FRAME_IGNORED.name.lowercase(Locale.ROOT),
                outcome = TraceOutcome.SKIPPED,
                attributes = mapOf(
                    "elapsedMs" to elapsedMillis().toString(),
                    "serverCode" to if (code in 0..4095) code.toString() else "out_of_range"
                )
            )
        }
    }

    fun finish(outcome: TraceOutcome, reason: Reason? = null) {
        if (!complete.compareAndSet(false, true)) return
        val attributes = linkedMapOf(
            "elapsedMs" to elapsedMillis().toString(),
            "ignoredServerFrames" to ignoredServerFrames.get().coerceAtMost(100000).toString()
        )
        if (reason != null) attributes["reasonCode"] = reason.name.lowercase(Locale.ROOT)
        continuation?.complete(outcome,attributes)
        trace.complete(outcome, attributes)
    }

    private fun elapsedMillis(): Long =
        ((monotonicNanos() - startNanos).coerceAtLeast(0L) / 1_000_000L)

    companion object {
        fun start(
            tracer: StructuredTracer,
            monotonicNanos: () -> Long = System::nanoTime,
            observedTransfer: Boolean = false
        ): A0SmokeTrace {
            val startedAt = monotonicNanos()
            val trace = tracer.startTrace(
                feature = TraceFeature.PROVIDER,
                operation = "a0_soulseek_one_shot",
                attributes = mapOf(
                    "environment" to "devdebug",
                    "mode" to if(observedTransfer)"manual_observed_one_file" else "read_only"
                )
            )
            return A0SmokeTrace(trace, tracer, startedAt, monotonicNanos)
        }
    }
}
