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
        NO_REPLY, NO_ELIGIBLE_CANDIDATE, SEARCH_COMPLETED, CANDIDATES_AVAILABLE,
        MANUAL_SELECTION_CANCELLED, P_F_TRANSFER_STARTED, P_F_TRANSFER_COMPLETED,
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
        trace.event(
            stage = stage.name.lowercase(Locale.ROOT),
            outcome = outcome,
            attributes = attributes
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
            return A0SmokeTrace(trace, startedAt, monotonicNanos)
        }
    }
}
