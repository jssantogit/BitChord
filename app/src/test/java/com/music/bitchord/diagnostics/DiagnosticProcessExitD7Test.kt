package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticProcessExitD7Test {
    @Test fun recoveredWatchdogStallIsNotAnAnr() {
        val finding = DiagnosticProcessExitForensics.classify(
            DiagnosticExitEvidence(recoveredStallMs = 1200, retainedUncaughtCrash = false),
        )
        assertEquals(DiagnosticExitKind.RECOVERED_STALL, finding.kind)
        assertEquals(DiagnosticEvidenceStrength.CONFIRMED, finding.strength)
        assertFalse(finding.provesAnr)
    }

    @Test fun aMissingCrashSnapshotNeverProvesForceStopOrAnr() {
        val finding = DiagnosticProcessExitForensics.classify(
            DiagnosticExitEvidence(previousProcessEnded = true, retainedUncaughtCrash = false),
        )
        assertEquals(DiagnosticExitKind.UNKNOWN_TERMINATION, finding.kind)
        assertEquals(DiagnosticEvidenceStrength.UNKNOWN, finding.strength)
    }

    @Test fun historicalPlatformAnrMustComeFromSupportedApi() {
        val finding = DiagnosticProcessExitForensics.classify(
            DiagnosticExitEvidence(previousProcessEnded = true, platformExitReason = "anr", platformApiLevel = 30),
        )
        assertEquals(DiagnosticExitKind.HISTORICAL_ANR, finding.kind)
        assertTrue(finding.provesAnr)
        val unsupported = DiagnosticProcessExitForensics.classify(
            DiagnosticExitEvidence(previousProcessEnded = true, platformExitReason = "anr", platformApiLevel = 26),
        )
        assertEquals(DiagnosticExitKind.UNKNOWN_TERMINATION, unsupported.kind)
    }

    @Test fun fatalHandlerSnapshotProvesExceptionObservedButNotUnderlyingCause() {
        val finding = DiagnosticProcessExitForensics.classify(
            DiagnosticExitEvidence(previousProcessEnded = true, retainedUncaughtCrash = true),
        )
        assertEquals(DiagnosticExitKind.UNCAUGHT_EXCEPTION_OBSERVED, finding.kind)
        assertEquals(DiagnosticEvidenceStrength.CONFIRMED, finding.strength)
        assertFalse(finding.provesAnr)
    }
}
