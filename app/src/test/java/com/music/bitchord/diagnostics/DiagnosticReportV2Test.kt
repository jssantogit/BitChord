package com.music.bitchord.diagnostics

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportV2Test {
    private fun event(seq: Long, level: DiagnosticLevel = DiagnosticLevel.INFO) =
        DiagnosticEvent(
            epochMillis = 1_700_000_000_000L + seq,
            monotonicNanos = seq * 100,
            sessionId = "s1",
            level = level,
            category = DiagnosticCategory.NETWORK,
            name = if (level == DiagnosticLevel.ERROR) "failure_recovery" else "trace_event",
            message = "uri=https://private.invalid/auth?token=secret",
            threadName = "test",
            durationNanos = null,
            metadata = mapOf("reasonCode" to "artwork_network_unavailable"),
            error = null,
            sequence = seq,
        )

    @Test fun unifiedReportDeduplicatesSnapshotAndCaptureBySessionSequence() {
        val e1 = event(1)
        val e2 = event(2, DiagnosticLevel.ERROR)
        val report = DiagnosticReportV2.build(
            DiagnosticReportV2Input(
                recentEvents = listOf(e1, e2),
                flushComplete = true,
                rejectedWrites = 0L,
                evictedMemoryEvents = 0L,
            ),
            captureLines = listOf(DiagnosticFormatter.format(e1), DiagnosticFormatter.format(e2)),
        )
        assertEquals(2, report.events.size)
        assertEquals(2, report.overlapCount)
        assertEquals("complete", report.integrityStatus)
        assertEquals(1, report.incidentCount)
        assertTrue(report.toHumanText().contains("schema=v2"))
        val json = report.toJsonl()
        assertEquals(2, json.lineSequence().filter(String::isNotBlank).count())
        assertFalse(json.contains("private.invalid"))
        assertFalse(json.contains("secret"))
        assertTrue(json.contains("\"sequence\":2"))
    }

    @Test fun incompleteFlushDropsAndLegacyLinesAreDeclaredNotInvented() {
        val result = DiagnosticReportV2.build(
            DiagnosticReportV2Input(
                recentEvents = listOf(event(2)),
                flushComplete = false,
                rejectedWrites = 7L,
                evictedMemoryEvents = 3L,
            ),
            captureLines = listOf("legacy unversioned capture line"),
        )
        assertEquals("partial_flush_timeout", result.integrityStatus)
        assertEquals(7L, result.rejectedWrites)
        assertEquals(3L, result.evictedMemoryEvents)
        assertEquals(1, result.legacyCaptureLines)
        assertEquals(1, result.events.size)
        assertTrue(result.toHumanText().contains("io_failures=unknown"))
        assertTrue(result.toHumanText().contains("capture_rotations=unknown"))
        assertFalse(result.toJsonl().contains("legacy unversioned"))
    }

    @Test fun twoSessionsDoNotCollapseMatchingSequenceNumbers() {
        val e = event(1)
        val report = DiagnosticReportV2.build(
            DiagnosticReportV2Input(
                recentEvents = listOf(e, e.copy(sessionId = "s2")),
                flushComplete = true,
                rejectedWrites = 0,
                evictedMemoryEvents = 0,
            ),
            captureLines = emptyList(),
        )
        assertEquals(2, report.events.size)
    }
}
