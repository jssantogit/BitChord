package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFunctionalLifecycleD9Test {
    private fun event(seq: Long, outcome: String = "fallback", flow: String? = null, attempt: String? = null) =
        DiagnosticEvent(
            epochMillis = 1_700_000_000_000L + seq,
            monotonicNanos = seq * 1_000L,
            sessionId = "session",
            level = DiagnosticLevel.WARN,
            category = DiagnosticCategory.NETWORK,
            name = "failure_recovery",
            message = null, threadName = "test",
            durationNanos = null,
            metadata = buildMap {
                put("reasonCode", "artwork_network_unavailable")
                put("recoveryOutcome", outcome)
                flow?.let { put("flowId", it) }
                attempt?.let { put("attemptRef", it) }
            },
            error = null, sequence = seq,
        )

    @Test fun sixtySixFallbackWarningsAreAClusterNotProvenRootCause() {
        val items = DiagnosticFailureIncidentSynthesizer.summarize((1L..66L).map { event(it) })
        assertEquals(1, items.size)
        assertEquals(66, items.single().occurrences)
        assertEquals(DiagnosticFunctionalTerminal.UNKNOWN, items.single().terminalOutcome)
        assertFalse(items.single().attemptBoundaryKnown)
        assertEquals("artwork_network_unavailable", items.single().reasonCode)
    }

    @Test fun twoExplicitAttemptsNeverDisappearIntoOneBurst() {
        val incidents = DiagnosticFailureIncidentSynthesizer.summarize(
            listOf(event(1, "retrying", "ref_flow", "ref_a"), event(2, "failed", "ref_flow", "ref_a"),
                event(3, "recovered", "ref_flow", "ref_b")),
        )
        assertEquals(2, incidents.size)
        assertEquals(setOf(DiagnosticFunctionalTerminal.FAILED, DiagnosticFunctionalTerminal.RECOVERED),
            incidents.map { it.terminalOutcome }.toSet())
        assertTrue(incidents.all { it.attemptBoundaryKnown })
    }

    @Test fun unsupportedIdentityFieldsAreNeverReturned() {
        val personal = event(1).copy(metadata = event(1).metadata +
            mapOf("flowId" to "rawTrackId", "attemptRef" to "token=secret"))
        val summary = DiagnosticFailureIncidentSynthesizer.summarize(listOf(personal)).single()
        assertEquals(null, summary.flowId)
        assertEquals(null, summary.attemptRef)
        assertFalse(summary.toString().contains("secret"))
    }
}
