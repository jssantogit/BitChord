package com.music.bitchord.diagnostics

import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceMode
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TracePointKind
import java.util.Locale

/** Controls free-text legacy detail independently from typed structured trace points. */
enum class DiagnosticMode {
    STANDARD,
    VERBOSE;

    fun allowsLegacy(level: DiagnosticLevel): Boolean =
        this == VERBOSE || level == DiagnosticLevel.WARN || level == DiagnosticLevel.ERROR

    fun toTraceMode(): TraceMode = if (this == VERBOSE) TraceMode.VERBOSE else TraceMode.STANDARD
}

data class StructuredTraceDiagnosticRecord(
    val category: DiagnosticCategory,
    val level: DiagnosticLevel,
    val name: String,
    val durationNanos: Long?,
    val metadata: Map<String, String>,
)

object StructuredTraceDiagnosticMapper {
    fun map(point: StructuredTracePoint): StructuredTraceDiagnosticRecord {
        val reserved = linkedMapOf(
            "traceId" to point.traceId,
            "feature" to point.feature.name.lowercase(Locale.ROOT),
            "operation" to point.operation,
            "outcome" to point.outcome.name.lowercase(Locale.ROOT),
            "verbosity" to point.verbosity.name.lowercase(Locale.ROOT),
        )
        point.stage?.let { reserved["stage"] = it }
        point.attributes.forEach { (key, value) ->
            if (key !in reserved && reserved.size < MAX_DIAGNOSTIC_METADATA_ENTRIES) {
                reserved[key] = value
            }
        }

        return StructuredTraceDiagnosticRecord(
            category = point.feature.toDiagnosticCategory(),
            level = point.outcome.toDiagnosticLevel(),
            name = point.kind.eventName,
            durationNanos = point.durationNanos,
            metadata = reserved,
        )
    }

    private fun TraceFeature.toDiagnosticCategory(): DiagnosticCategory = when (this) {
        TraceFeature.APP -> DiagnosticCategory.APP
        TraceFeature.UI -> DiagnosticCategory.UI
        TraceFeature.PLAYBACK -> DiagnosticCategory.PLAYER
        TraceFeature.CATALOG -> DiagnosticCategory.CATALOG
        TraceFeature.PROVIDER -> DiagnosticCategory.NETWORK
        TraceFeature.RECOMMENDATION -> DiagnosticCategory.OTHER
        TraceFeature.DATABASE -> DiagnosticCategory.DATABASE
        TraceFeature.IO -> DiagnosticCategory.OTHER
    }

    private fun TraceOutcome.toDiagnosticLevel(): DiagnosticLevel = when (this) {
        TraceOutcome.FAILED -> DiagnosticLevel.ERROR
        TraceOutcome.DEGRADED -> DiagnosticLevel.WARN
        TraceOutcome.STARTED,
        TraceOutcome.SUCCEEDED,
        TraceOutcome.CANCELLED,
        TraceOutcome.SKIPPED -> DiagnosticLevel.INFO
    }

    private val TracePointKind.eventName: String
        get() = when (this) {
            TracePointKind.TRACE_START -> "trace_start"
            TracePointKind.EVENT -> "trace_event"
            TracePointKind.SPAN -> "trace_span"
            TracePointKind.TRACE_END -> "trace_end"
        }

    private const val MAX_DIAGNOSTIC_METADATA_ENTRIES = 32
}

/** Android diagnostics adapter. Domain code sees only [StructuredTraceSink]. */
object DiagnosticsStructuredTraceSink : StructuredTraceSink {
    override fun record(point: StructuredTracePoint) {
        val record = StructuredTraceDiagnosticMapper.map(point)
        Diagnostics.event(
            category = record.category,
            name = record.name,
            level = record.level,
            metadata = record.metadata,
            durationNanos = record.durationNanos,
        )
    }
}
