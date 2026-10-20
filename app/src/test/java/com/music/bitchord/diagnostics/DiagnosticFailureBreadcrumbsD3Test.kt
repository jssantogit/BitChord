package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFailureBreadcrumbsD3Test {
    @Test fun typedReasonCodesAndOutcomesRemainLowCardinalityAndSanitized() {
        val events = mutableListOf<DiagnosticFailureBreadcrumb>()
        val previous = DiagnosticFailureBreadcrumbs.sink
        try {
            DiagnosticFailureBreadcrumbs.sink = DiagnosticFailureSink { events += it }
            DiagnosticFailureBreadcrumbs.record(
                category = DiagnosticCategory.CATALOG,
                reason = DiagnosticFailureReason.PROVIDER_SNAPSHOT_FAILED,
                outcome = DiagnosticRecoveryOutcome.FALLBACK,
            )
            DiagnosticFailureBreadcrumbs.record(
                category = DiagnosticCategory.NETWORK,
                reason = DiagnosticFailureReason.ARTWORK_CACHE_READ_FAILED,
                outcome = DiagnosticRecoveryOutcome.RETRYING,
            )
            DiagnosticFailureBreadcrumbs.record(
                category = DiagnosticCategory.NETWORK,
                reason = DiagnosticFailureReason.ARTWORK_NETWORK_UNAVAILABLE,
                outcome = DiagnosticRecoveryOutcome.DEGRADED,
            )
            assertEquals(3, events.size)
            assertEquals("provider_snapshot_failed", events.first().reason.wireName)
            assertEquals("fallback", events.first().outcome.wireName)
            assertFalse(events.joinToString().contains("http"))
            assertTrue(events.all { it.reason.wireName == it.reason.wireName.lowercase() })
        } finally {
            DiagnosticFailureBreadcrumbs.sink = previous
        }
    }

    @Test fun failingDiagnosticSinkNeverChangesCallerBehavior() {
        val old = DiagnosticFailureBreadcrumbs.sink
        try {
            DiagnosticFailureBreadcrumbs.sink = DiagnosticFailureSink { error("diagnostics offline") }
            var originalActionRan = false
            DiagnosticFailureBreadcrumbs.record(
                category = DiagnosticCategory.NETWORK,
                reason = DiagnosticFailureReason.ARTWORK_TRANSPORT_EXCEPTION,
                outcome = DiagnosticRecoveryOutcome.FALLBACK,
            )
            originalActionRan = true
            assertTrue(originalActionRan)
        } finally {
            DiagnosticFailureBreadcrumbs.sink = old
        }
    }

    @Test fun untrustedExceptionMessageCannotBeAcceptedAsReasonCode() {
        assertFalse(DiagnosticFailureReason.entries.any { it.wireName.contains("token") })
        assertFalse(DiagnosticFailureReason.entries.any { it.wireName.contains("url") })
        assertTrue(DiagnosticFailureReason.entries.size <= 24)
    }
}
