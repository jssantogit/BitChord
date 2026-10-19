package com.music.bitchord.data.sources

import com.music.bitchord.observability.StructuredTrace
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome

/**
 * Pure trace seam for the source-backed playback path. Provider work always runs,
 * even when tracing is disabled or the bounded trace budget has been exhausted.
 */
internal fun interface SourceResolutionTraceReporter {
    fun start(sourceConfigId: String, trackId: String): SourceResolutionRunTrace

    companion object {
        val NO_OP: SourceResolutionTraceReporter = SourceResolutionTraceReporter { _, _ ->
            NoOpSourceResolutionTrace
        }
    }
}

internal interface SourceResolutionRunTrace {
    suspend fun <T> lookup(block: suspend () -> T): T
    fun complete(found: Boolean)
    fun cancel()
    fun fail()
}

private object NoOpSourceResolutionTrace : SourceResolutionRunTrace {
    override suspend fun <T> lookup(block: suspend () -> T): T = block()
    override fun complete(found: Boolean) = Unit
    override fun cancel() = Unit
    override fun fail() = Unit
}

/** Never emits raw config IDs, track IDs, stream URLs, headers or exception messages. */
internal class StructuredSourceResolutionTraceReporter(
    private val tracer: StructuredTracer,
) : SourceResolutionTraceReporter {
    override fun start(sourceConfigId: String, trackId: String): SourceResolutionRunTrace {
        val trace = tracer.startTrace(
            feature = TraceFeature.PROVIDER,
            operation = "source_stream_resolution",
            attributes = mapOf(
                "sourceRef" to tracer.reference(sourceConfigId),
                "trackRef" to tracer.reference(trackId),
            ),
        )
        return StructuredSourceResolutionRunTrace(trace)
    }
}

private class StructuredSourceResolutionRunTrace(
    private val trace: StructuredTrace,
) : SourceResolutionRunTrace {
    override suspend fun <T> lookup(block: suspend () -> T): T =
        trace.spanSuspend(stage = "select_stream", block = block)

    override fun complete(found: Boolean) {
        val outcome = if (found) TraceOutcome.SUCCEEDED else TraceOutcome.SKIPPED
        trace.event(
            stage = "stream_result",
            outcome = outcome,
            attributes = mapOf("availability" to if (found) "found" else "missing"),
        )
        trace.complete(outcome)
    }

    override fun cancel() {
        trace.complete(TraceOutcome.CANCELLED)
    }

    override fun fail() {
        trace.complete(TraceOutcome.FAILED)
    }
}
