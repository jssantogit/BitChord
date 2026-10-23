package com.music.bitchord.diagnostics

import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEvidenceJourneyD11Test {
    @Test fun missingRatingIsNotAZeroOrSuccess() {
        val points = mutableListOf<StructuredTracePoint>()
        val probe = DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { points.add(it) }))
        val journey = probe.begin(EvidenceJourney.REMOTE_RATINGS)!!
        journey.observe(EvidenceStep.REMOTE_RATING_FIELD, EvidenceTruth.ABSENT)
        journey.observe(EvidenceStep.CANONICAL_MATCH, EvidenceTruth.UNKNOWN)
        journey.finish(TraceOutcome.DEGRADED)
        assertEquals("absent", points.first { it.stage == "remote_rating_field" }.attributes["truth"])
        assertFalse(points.any { it.attributes["count"] == "0" })
        assertEquals(TraceOutcome.DEGRADED, points.last().outcome)
    }

    @Test fun confirmedZeroRowsIsDifferentFromUnknownCount() {
        val points = mutableListOf<StructuredTracePoint>()
        val journey = DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { points.add(it) }))
            .begin(EvidenceJourney.MY_RATINGS)!!
        journey.observe(EvidenceStep.ROOM_QUERY, EvidenceTruth.OBSERVED, count = 0)
        journey.observe(EvidenceStep.UI_EMPTY_STATE, EvidenceTruth.UNKNOWN)
        journey.finish(TraceOutcome.SKIPPED)
        assertEquals("0", points.first { it.stage == "room_query" }.attributes["count"])
        assertNull(points.first { it.stage == "ui_empty_state" }.attributes["count"])
    }

    @Test fun parentPropagationKeepsFlowWithoutRawIdentifiers() {
        val points = mutableListOf<StructuredTracePoint>()
        val tracer = StructuredTracer(sink = { points.add(it) })
        val probe = DiagnosticEvidenceJourneyRecorder(tracer)
        val parent = probe.begin(EvidenceJourney.MY_RATINGS)!!
        val child = probe.begin(EvidenceJourney.REMOTE_RATINGS, parent.context())!!
        child.observe(EvidenceStep.PROVIDER_ENUMERATION, EvidenceTruth.OBSERVED, count = 50000)
        child.finish(TraceOutcome.SUCCEEDED)
        parent.finish(TraceOutcome.DEGRADED)
        val starts = points.filter { it.stage == null && it.outcome == TraceOutcome.STARTED }
        assertEquals(starts[0].flowId, starts[1].flowId)
        assertTrue(starts[1].parentSpanId!!.startsWith("ref_"))
        assertEquals("1000_plus", points.first { it.stage == "provider_enumeration" }.attributes["count"])
        assertFalse(points.any { it.attributes.values.any { v -> v.contains("50000") } })
    }

    @Test fun disabledCaptureAndBrokenSinkNeverChangeProduct() {
        val disabled = DiagnosticEvidenceJourneyRecorder(StructuredTracer(), active = { false })
        assertNull(disabled.begin(EvidenceJourney.NOW_PLAYING))
        val throwing = DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { error("sink failed") }))
        assertNull(throwing.begin(EvidenceJourney.MY_RATINGS))
        val points = mutableListOf<StructuredTracePoint>()
        val probe = DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { point ->
            if (point.stage != null) error("event failure")
            points.add(point)
        }))
        val journey = probe.begin(EvidenceJourney.MY_RATINGS)!!
        journey.observe(EvidenceStep.UI_COMPOSED, EvidenceTruth.OBSERVED)
        journey.finish(TraceOutcome.FAILED)
        assertTrue(points.isNotEmpty())
    }

    @Test fun lastTerminalOutcomeCannotBeOverwritten() {
        val points = mutableListOf<StructuredTracePoint>()
        val journey = DiagnosticEvidenceJourneyRecorder(StructuredTracer(sink = { points.add(it) }))
            .begin(EvidenceJourney.NOW_PLAYING)!!
        journey.finish(TraceOutcome.CANCELLED)
        journey.finish(TraceOutcome.SUCCEEDED)
        assertEquals(1, points.count { it.stage == null && it.outcome == TraceOutcome.CANCELLED })
        assertNotEquals(TraceOutcome.SUCCEEDED, points.last().outcome)
    }
}
