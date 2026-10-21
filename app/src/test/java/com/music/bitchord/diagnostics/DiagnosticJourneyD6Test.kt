package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticJourneyD6Test {
    private fun point(
        seq: Long, stage: String?, outcome: String,
        flow: String = "ref_flow_a", session: String = "session_a",
        name: String = if (stage == null) "trace_end" else "trace_event",
    ) = DiagnosticEvent(
        epochMillis = 1_700_000_000_000L + seq,
        monotonicNanos = seq * 100L,
        sessionId = session,
        level = DiagnosticLevel.INFO,
        category = DiagnosticCategory.PLAYER,
        name = name,
        message = null,
        threadName = "test",
        durationNanos = null,
        metadata = mapOf("flowId" to flow, "stage" to (stage ?: ""), "outcome" to outcome),
        error = null,
        sequence = seq,
    )

    @Test fun missingBoundaryRemainsUnknownRatherThanSucceeded() {
        val timeline = DiagnosticJourneyReconstructor.reconstruct(
            events = listOf(
                point(3, "resolver", "succeeded"),
                point(1, "ui_action", "succeeded"),
                point(2, "queue_commit", "succeeded", flow = "ref_other"),
            ),
            flowId = "ref_flow_a",
            expectedStages = listOf("ui_action", "queue_commit", "resolver", "audible"),
        )
        assertEquals("resolver", timeline.lastConfirmedStage)
        assertEquals("queue_commit", timeline.firstUnknownStage)
        assertEquals(DiagnosticJourneyOutcome.UNKNOWN, timeline.terminalOutcome)
        assertEquals(2, timeline.evidence.size)
        assertFalse(timeline.completed)
    }

    @Test fun explicitCancellationIsNotFunctionalFailureOrSuccess() {
        val timeline = DiagnosticJourneyReconstructor.reconstruct(
            listOf(point(1, "ui_action", "succeeded"), point(2, null, "cancelled")),
            "ref_flow_a", listOf("ui_action", "observable_result"),
        )
        assertEquals(DiagnosticJourneyOutcome.CANCELLED, timeline.terminalOutcome)
        assertEquals("observable_result", timeline.firstUnknownStage)
    }

    @Test fun twoSessionsAndOutOfOrderCallbacksDoNotForgeAResult() {
        val timeline = DiagnosticJourneyReconstructor.reconstruct(
            listOf(
                point(5, "ready", "succeeded", session = "b"),
                point(1, "intent", "succeeded"),
                point(2, "ready", "failed"),
            ),
            "ref_flow_a", listOf("intent", "ready"),
        )
        assertEquals(DiagnosticJourneyOutcome.UNKNOWN, timeline.terminalOutcome)
        assertTrue(timeline.evidence.any { it.outcome == DiagnosticJourneyOutcome.FAILED })
        assertFalse(timeline.completed)
    }
}
