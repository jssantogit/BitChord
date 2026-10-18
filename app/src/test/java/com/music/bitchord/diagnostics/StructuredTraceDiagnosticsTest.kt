package com.music.bitchord.diagnostics

import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.observability.TracePointKind
import com.music.bitchord.observability.TraceVerbosity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredTraceDiagnosticsTest {
    @Test
    fun `standard mode suppresses legacy debug info but preserves warnings errors`() {
        assertFalse(DiagnosticMode.STANDARD.allowsLegacy(DiagnosticLevel.DEBUG))
        assertFalse(DiagnosticMode.STANDARD.allowsLegacy(DiagnosticLevel.INFO))
        assertTrue(DiagnosticMode.STANDARD.allowsLegacy(DiagnosticLevel.WARN))
        assertTrue(DiagnosticMode.STANDARD.allowsLegacy(DiagnosticLevel.ERROR))
    }

    @Test
    fun `verbose mode allows all legacy levels`() {
        DiagnosticLevel.entries.forEach { level ->
            assertTrue(DiagnosticMode.VERBOSE.allowsLegacy(level))
        }
    }

    @Test
    fun `structured trace mapper emits fixed taxonomy and correlation metadata`() {
        val record = StructuredTraceDiagnosticMapper.map(
            StructuredTracePoint(
                traceId = "trace-123",
                feature = TraceFeature.PLAYBACK,
                operation = "seek",
                stage = "ready",
                kind = TracePointKind.SPAN,
                outcome = TraceOutcome.SUCCEEDED,
                durationNanos = 42_000_000L,
                attributes = mapOf(
                    "candidateRef" to "ref_abc123",
                    "queueDepth" to "3",
                ),
                verbosity = TraceVerbosity.STANDARD,
            ),
        )

        assertEquals(DiagnosticCategory.PLAYER, record.category)
        assertEquals(DiagnosticLevel.INFO, record.level)
        assertEquals("trace_span", record.name)
        assertEquals(42_000_000L, record.durationNanos)
        assertEquals("trace-123", record.metadata["traceId"])
        assertEquals("playback", record.metadata["feature"])
        assertEquals("seek", record.metadata["operation"])
        assertEquals("ready", record.metadata["stage"])
        assertEquals("succeeded", record.metadata["outcome"])
        assertEquals("ref_abc123", record.metadata["candidateRef"])
        assertEquals("3", record.metadata["queueDepth"])
    }

    @Test
    fun `failed and degraded structured outcomes surface with useful severity`() {
        val failed = StructuredTraceDiagnosticMapper.map(point(TraceOutcome.FAILED))
        val degraded = StructuredTraceDiagnosticMapper.map(point(TraceOutcome.DEGRADED))
        val cancelled = StructuredTraceDiagnosticMapper.map(point(TraceOutcome.CANCELLED))

        assertEquals(DiagnosticLevel.ERROR, failed.level)
        assertEquals(DiagnosticLevel.WARN, degraded.level)
        assertEquals(DiagnosticLevel.INFO, cancelled.level)
    }

    @Test
    fun `all structured point kinds map to stable low cardinality event names`() {
        val names = TracePointKind.entries.associateWith { kind ->
            StructuredTraceDiagnosticMapper.map(point(kind = kind)).name
        }

        assertEquals("trace_start", names[TracePointKind.TRACE_START])
        assertEquals("trace_event", names[TracePointKind.EVENT])
        assertEquals("trace_span", names[TracePointKind.SPAN])
        assertEquals("trace_end", names[TracePointKind.TRACE_END])
    }

    private fun point(
        outcome: TraceOutcome = TraceOutcome.SUCCEEDED,
        kind: TracePointKind = TracePointKind.EVENT,
    ) = StructuredTracePoint(
        traceId = "trace",
        feature = TraceFeature.RECOMMENDATION,
        operation = "library_rank",
        stage = "candidate_generation",
        kind = kind,
        outcome = outcome,
        durationNanos = null,
        attributes = emptyMap(),
        verbosity = TraceVerbosity.STANDARD,
    )
}
