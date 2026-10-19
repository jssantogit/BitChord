package com.music.bitchord.observability

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Capture detail policy. Standard stays compact; Verbose is an explicit diagnostic opt-in. */
enum class TraceMode {
    STANDARD,
    VERBOSE,
}

enum class TraceVerbosity {
    STANDARD,
    VERBOSE,
}

/** Stable, low-cardinality ownership categories for structured diagnostics. */
enum class TraceFeature {
    APP,
    UI,
    PLAYBACK,
    CATALOG,
    PROVIDER,
    RECOMMENDATION,
    DATABASE,
    IO,
}

enum class TraceOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SKIPPED,
    DEGRADED,
}

enum class TracePointKind {
    TRACE_START,
    EVENT,
    SPAN,
    TRACE_END,
}

data class StructuredTracePoint(
    val traceId: String,
    val feature: TraceFeature,
    val operation: String,
    val stage: String?,
    val kind: TracePointKind,
    val outcome: TraceOutcome,
    val durationNanos: Long?,
    val attributes: Map<String, String>,
    val verbosity: TraceVerbosity,
)

fun interface StructuredTraceSink {
    fun record(point: StructuredTracePoint)

    companion object {
        val NO_OP = StructuredTraceSink { }
    }
}

/**
 * Converts a sensitive/internal identity to a deterministic reference that is useful only
 * inside one diagnostics session. The session salt deliberately prevents correlation across
 * captures without persisting the original identity.
 */
class SessionTraceReferenceEncoder(
    private val sessionSalt: String,
) {
    init {
        require(sessionSalt.isNotBlank()) { "sessionSalt must not be blank" }
    }

    fun encode(rawValue: String): String {
        require(rawValue.isNotBlank()) { "rawValue must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$sessionSalt\u0000$rawValue".toByteArray(StandardCharsets.UTF_8),
        )
        return buildString(4 + REFERENCE_BYTES * 2) {
            append("ref_")
            repeat(REFERENCE_BYTES) { index ->
                append(HEX[(digest[index].toInt() ushr 4) and 0x0f])
                append(HEX[digest[index].toInt() and 0x0f])
            }
        }
    }

    private companion object {
        const val REFERENCE_BYTES = 10
        const val HEX = "0123456789abcdef"
    }
}

/**
 * Framework-neutral structured tracing seam. A trace handle is passed explicitly, so
 * correlation survives coroutine/thread hops without thread-local state.
 */
class StructuredTracer(
    mode: TraceMode = TraceMode.STANDARD,
    private val sink: StructuredTraceSink = StructuredTraceSink.NO_OP,
    private val traceIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val referenceEncoder: SessionTraceReferenceEncoder = SessionTraceReferenceEncoder(
        UUID.randomUUID().toString(),
    ),
    private val maxIntermediatePoints: Int = DEFAULT_MAX_INTERMEDIATE_POINTS,
) {
    init {
        require(maxIntermediatePoints >= 0) { "maxIntermediatePoints must not be negative" }
    }

    @Volatile
    var mode: TraceMode = mode

    /** Encode privacy-safe references before emitting TRACE_START metadata. */
    fun reference(rawValue: String): String = referenceEncoder.encode(rawValue)

    fun startTrace(
        feature: TraceFeature,
        operation: String,
        attributes: Map<String, String> = emptyMap(),
    ): StructuredTrace {
        val traceId = traceIdFactory().take(MAX_TRACE_ID_CHARS)
        require(traceId.isNotBlank()) { "traceId must not be blank" }
        val startedAt = monotonicNanos()
        val trace = StructuredTrace(
            traceId = traceId,
            feature = feature,
            operation = cleanToken(operation, "operation"),
            startedAtNanos = startedAt,
            modeProvider = { mode },
            sink = sink,
            monotonicNanos = monotonicNanos,
            referenceEncoder = referenceEncoder,
            maxIntermediatePoints = maxIntermediatePoints,
        )
        trace.emitStart(attributes)
        return trace
    }

    private companion object {
        const val MAX_TRACE_ID_CHARS = 80
        const val DEFAULT_MAX_INTERMEDIATE_POINTS = 64
    }
}

class StructuredTrace internal constructor(
    val traceId: String,
    val feature: TraceFeature,
    val operation: String,
    private val startedAtNanos: Long,
    private val modeProvider: () -> TraceMode,
    private val sink: StructuredTraceSink,
    private val monotonicNanos: () -> Long,
    private val referenceEncoder: SessionTraceReferenceEncoder,
    maxIntermediatePoints: Int,
) {
    private val completed = AtomicBoolean(false)
    private val remainingIntermediatePoints = AtomicInteger(maxIntermediatePoints)

    internal fun emitStart(attributes: Map<String, String>) {
        emit(
            stage = null,
            kind = TracePointKind.TRACE_START,
            outcome = TraceOutcome.STARTED,
            durationNanos = null,
            attributes = attributes,
            verbosity = TraceVerbosity.STANDARD,
        )
    }

    fun reference(rawValue: String): String = referenceEncoder.encode(rawValue)

    fun event(
        stage: String,
        outcome: TraceOutcome,
        attributes: Map<String, String> = emptyMap(),
        verbosity: TraceVerbosity = TraceVerbosity.STANDARD,
    ) {
        if (completed.get()) return
        emit(
            stage = cleanToken(stage, "stage"),
            kind = TracePointKind.EVENT,
            outcome = outcome,
            durationNanos = null,
            attributes = attributes,
            verbosity = verbosity,
        )
    }

    fun <T> span(
        stage: String,
        attributes: Map<String, String> = emptyMap(),
        verbosity: TraceVerbosity = TraceVerbosity.STANDARD,
        block: () -> T,
    ): T {
        val cleanStage = cleanToken(stage, "stage")
        val started = monotonicNanos()
        return try {
            val result = block()
            emitSpan(cleanStage, TraceOutcome.SUCCEEDED, started, attributes, verbosity)
            result
        } catch (error: Throwable) {
            emitSpan(
                cleanStage,
                TraceOutcome.FAILED,
                started,
                attributes + ("errorType" to error.javaClass.simpleName),
                verbosity,
            )
            throw error
        }
    }

    suspend fun <T> spanSuspend(
        stage: String,
        attributes: Map<String, String> = emptyMap(),
        verbosity: TraceVerbosity = TraceVerbosity.STANDARD,
        block: suspend () -> T,
    ): T {
        val cleanStage = cleanToken(stage, "stage")
        val started = monotonicNanos()
        return try {
            val result = block()
            emitSpan(cleanStage, TraceOutcome.SUCCEEDED, started, attributes, verbosity)
            result
        } catch (error: Throwable) {
            emitSpan(
                cleanStage,
                TraceOutcome.FAILED,
                started,
                attributes + ("errorType" to error.javaClass.simpleName),
                verbosity,
            )
            throw error
        }
    }

    fun complete(
        outcome: TraceOutcome,
        attributes: Map<String, String> = emptyMap(),
    ) {
        require(outcome != TraceOutcome.STARTED) { "completed trace cannot have STARTED outcome" }
        if (!completed.compareAndSet(false, true)) return
        val finishedAt = monotonicNanos()
        emit(
            stage = null,
            kind = TracePointKind.TRACE_END,
            outcome = outcome,
            durationNanos = nonNegativeDuration(startedAtNanos, finishedAt),
            attributes = attributes,
            verbosity = TraceVerbosity.STANDARD,
            bypassCompletedCheck = true,
        )
    }

    private fun emitSpan(
        stage: String,
        outcome: TraceOutcome,
        started: Long,
        attributes: Map<String, String>,
        verbosity: TraceVerbosity,
    ) {
        if (completed.get()) return
        emit(
            stage = stage,
            kind = TracePointKind.SPAN,
            outcome = outcome,
            durationNanos = nonNegativeDuration(started, monotonicNanos()),
            attributes = attributes,
            verbosity = verbosity,
        )
    }

    private fun emit(
        stage: String?,
        kind: TracePointKind,
        outcome: TraceOutcome,
        durationNanos: Long?,
        attributes: Map<String, String>,
        verbosity: TraceVerbosity,
        bypassCompletedCheck: Boolean = false,
    ) {
        if (!bypassCompletedCheck && completed.get()) return
        if (verbosity == TraceVerbosity.VERBOSE && modeProvider() != TraceMode.VERBOSE) return
        if (kind.isIntermediate && !claimIntermediatePoint()) return
        sink.record(
            StructuredTracePoint(
                traceId = traceId,
                feature = feature,
                operation = operation,
                stage = stage,
                kind = kind,
                outcome = outcome,
                durationNanos = durationNanos,
                attributes = boundedAttributes(attributes),
                verbosity = verbosity,
            ),
        )
    }

    private fun claimIntermediatePoint(): Boolean {
        while (true) {
            val remaining = remainingIntermediatePoints.get()
            if (remaining <= 0) return false
            if (remainingIntermediatePoints.compareAndSet(remaining, remaining - 1)) return true
        }
    }

    private val TracePointKind.isIntermediate: Boolean
        get() = this == TracePointKind.EVENT || this == TracePointKind.SPAN
}

private fun cleanToken(value: String, field: String): String {
    val clean = value.trim().take(MAX_TOKEN_CHARS)
    require(clean.isNotBlank()) { "$field must not be blank" }
    return clean
}

private fun boundedAttributes(values: Map<String, String>): Map<String, String> =
    values.entries
        .take(MAX_ATTRIBUTE_ENTRIES)
        .associate { (key, value) ->
            key.trim().take(MAX_ATTRIBUTE_KEY_CHARS) to value.take(MAX_ATTRIBUTE_VALUE_CHARS)
        }
        .filterKeys(String::isNotBlank)

private fun nonNegativeDuration(started: Long, finished: Long): Long =
    if (finished >= started) finished - started else 0L

private const val MAX_TOKEN_CHARS = 96
private const val MAX_ATTRIBUTE_ENTRIES = 24
private const val MAX_ATTRIBUTE_KEY_CHARS = 64
private const val MAX_ATTRIBUTE_VALUE_CHARS = 512
