package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIncidentTriageD10Test {
    private fun event(seq: Long, name: String = "failure_recovery") = DiagnosticEvent(
        epochMillis = 1_700_000_000_000L + seq,
        monotonicNanos = seq * 1_000L,
        sessionId = "session_safe",
        level = DiagnosticLevel.WARN,
        category = if (name == "main_thread_stall") DiagnosticCategory.UI else DiagnosticCategory.NETWORK,
        name = name,
        message = "token=private123 https://private.invalid/media",
        threadName = "test",
        durationNanos = if (name == "main_thread_stall") 1_000_000_000L else null,
        metadata = mapOf("reasonCode" to "artwork_network_unavailable", "recoveryOutcome" to "fallback"),
        error = null, sequence = seq,
    )

    @Test fun shareableIncidentIsEvidenceBackedNotRootCauseGuess() {
        val report = DiagnosticIncidentTriage.build(
            (1L..66L).map { event(it) }, rejectedWrites = 2L, flushComplete = false,
        )
        assertEquals(1, report.incidents.size)
        assertEquals(66, report.incidents.single().occurrences)
        assertEquals(DiagnosticFunctionalTerminal.UNKNOWN, report.incidents.single().outcome)
        assertEquals("unknown", report.incidents.single().causeStrength)
        assertTrue(report.toHumanLines().any { it.contains("partial") })
        assertTrue(report.toJsonl().contains("\"schema\":3"))
        assertFalse(report.toJsonl().contains("private123"))
        assertFalse(report.toHumanLines().joinToString().contains("private.invalid"))
    }

    @Test fun recoveredStallNeverBecomesAConfirmedAnr() {
        val report = DiagnosticIncidentTriage.build(listOf(event(1, "main_thread_stall")))
        assertEquals("recovered_stall", report.incidents.single().reasonCode)
        assertEquals(DiagnosticFunctionalTerminal.UNKNOWN, report.incidents.single().outcome)
        assertFalse(report.toHumanLines().joinToString().contains("confirmed_anr"))
    }

    @Test fun triageFiltersWindowCategoryAndOutcome() {
        val report = DiagnosticIncidentTriage.build(listOf(event(1), event(2, "main_thread_stall")))
        val filtered = report.filter(
            DiagnosticIncidentFilter(category = DiagnosticCategory.UI, sinceUtcMillis = 1_700_000_000_002L),
        )
        assertEquals(1, filtered.size)
        assertEquals(DiagnosticCategory.UI, filtered.single().category)
        assertTrue(report.filter(DiagnosticIncidentFilter(outcome = DiagnosticFunctionalTerminal.RECOVERED)).isEmpty())
    }
}
