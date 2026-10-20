package com.music.bitchord.playback

import com.music.bitchord.observability.StructuredTrace
import com.music.bitchord.observability.TraceFlowContext
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome

/**
 * Read-only Media3 command-to-observable-result trace. One pending play is tracked;
 * pauses, item changes, failures, superseding commands and the bounded timeout all
 * close the trace exactly once. No callback here modifies the actual player.
 */
internal class PlaybackE2ETraceObserver(
    private val tracer: StructuredTracer,
    private val scheduleTimeout: (Long, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
    private val timeoutMillis: Long = 30_000L,
) {
    init {
        require(timeoutMillis > 0L)
    }

    private class Pending(
        val itemId: String?,
        val trace: StructuredTrace,
    ) {
        var ready = false
        var cancelTimeout: () -> Unit = {}
    }

    private var active: Pending? = null

    /** A playWhenReady=true callback confirms the Media3 player accepted play intent. */
    fun onPlayAccepted(mediaId: String?, parent: TraceFlowContext? = null) {
        close(TraceOutcome.CANCELLED, "superseded")
        val trace = tracer.startTrace(
            feature = TraceFeature.PLAYBACK,
            operation = "play_to_audible",
            parent = parent,
        )
        val pending = Pending(mediaId, trace)
        active = pending
        trace.event(
            stage = "command_accepted",
            outcome = TraceOutcome.STARTED,
            attributes = mediaId?.takeIf(String::isNotBlank)?.let {
                mapOf("itemRef" to trace.reference(it))
            } ?: emptyMap(),
        )
        pending.cancelTimeout = scheduleTimeout(timeoutMillis) {
            if (active === pending) {
                close(TraceOutcome.DEGRADED, "timed_out")
            }
        }
    }

    fun onReady(mediaId: String?) {
        val pending = matching(mediaId) ?: return
        if (pending.ready) return
        pending.ready = true
        pending.trace.event(stage = "media3_ready", outcome = TraceOutcome.SUCCEEDED)
    }

    fun onAudible(mediaId: String?) {
        val pending = matching(mediaId) ?: return
        pending.trace.event(stage = "audible", outcome = TraceOutcome.SUCCEEDED)
        close(TraceOutcome.SUCCEEDED, "audible")
    }

    fun onMediaChanged(mediaId: String?) {
        matching(mediaId)
    }

    fun onPaused() {
        close(TraceOutcome.CANCELLED, "paused")
    }

    fun onError() {
        close(TraceOutcome.FAILED, "player_error")
    }

    private fun matching(mediaId: String?): Pending? {
        val pending = active ?: return null
        if (pending.itemId != null && mediaId != null && pending.itemId != mediaId) {
            close(TraceOutcome.CANCELLED, "item_changed")
            return null
        }
        return pending
    }

    private fun close(outcome: TraceOutcome, stage: String) {
        val pending = active ?: return
        active = null
        pending.cancelTimeout()
        pending.trace.complete(outcome, attributes = mapOf("terminalStage" to stage))
    }
}
