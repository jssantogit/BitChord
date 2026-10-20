package com.music.bitchord.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticIncidentExportIntegrationD10Test {
    @get:Rule val temp = TemporaryFolder()

    private fun point(sequence: Int, isSpan: Boolean) = DiagnosticEvent(
        epochMillis = 1_700_000_000_000L + sequence,
        monotonicNanos = sequence * 1000L,
        sessionId = "process_session",
        level = DiagnosticLevel.WARN,
        category = DiagnosticCategory.NETWORK,
        name = if (isSpan) "trace_span" else "failure_recovery",
        message = "https://private.example/stream?token=super-secret",
        threadName = "test",
        durationNanos = if (isSpan) (sequence * 1_000_000L) else null,
        metadata = if (isSpan) mapOf(
            "feature" to "provider",
            "operation" to "stream_resolve",
            "phase" to "external",
            "flowId" to "ref_safe_flow",
        ) else mapOf(
            "reasonCode" to "artwork_network_unavailable",
            "recoveryOutcome" to "fallback",
            "flowId" to "ref_safe_flow",
        ),
        error = null,
        sequence = sequence.toLong(),
    )

    @Test fun exportedReportLinksSafeFlowAndMeasuredDelaysWithoutClaimingCause() {
        val events = (1..25).map { point(it, true) } + point(26, false)
        val triage = DiagnosticIncidentTriage.build(events)
        val measured = DiagnosticBottleneckAnalysis.summarize(events, captureEnabled = true)
        val store = DiagnosticFileStore(temp.newFolder(), 1024 * 1024L)
        val text = store.createExport(
            metadataLines = listOf("build=dev"),
            recentEventLines = events.map(DiagnosticFormatter::format),
            fileName = "test.txt",
            reportV2Input = DiagnosticReportV2Input(
                recentEvents = events, flushComplete = true,
                rejectedWrites = 0, evictedMemoryEvents = 0,
            ),
            incidentReport = triage,
            performanceReport = measured,
        ).readText()
        assertTrue(text.contains("[incident-triage-v3]"))
        assertTrue(text.contains("[incidents-jsonl-v3]"))
        assertTrue(text.contains("\"flowId\":\"ref_safe_flow\""))
        assertTrue(text.contains("[measured-bottlenecks-v0]"))
        assertTrue(text.contains("p95_ns="))
        assertTrue(text.contains("critical_path_proven=false"))
        assertTrue(text.contains("[report-v2]"))
        assertFalse(text.contains("private.example"))
        assertFalse(text.contains("super-secret"))
        assertFalse(text.contains("root_cause=confirmed"))
    }
}
