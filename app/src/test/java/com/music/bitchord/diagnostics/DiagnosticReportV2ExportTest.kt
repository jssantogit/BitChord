package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticReportV2ExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun realFileStoreKeepsLegacyTxtAndAddsDeduplicatedV2OptionalJsonl() {
        val event = DiagnosticEvent(
            epochMillis = 1_700_000_000_000L,
            monotonicNanos = 123L,
            sessionId = "s1",
            level = DiagnosticLevel.ERROR,
            category = DiagnosticCategory.NETWORK,
            name = "failure_recovery",
            message = "https://private.invalid/?token=secret",
            threadName = "test",
            durationNanos = null,
            metadata = emptyMap(),
            error = null,
            sequence = 1,
        )
        val store = DiagnosticFileStore(temp.newFolder(), 1024L)
        store.appendCapture(DiagnosticFormatter.format(event))
        val export = store.createExport(
            metadataLines = listOf("build=dev"),
            recentEventLines = listOf(DiagnosticFormatter.format(event)),
            fileName = "test.txt",
            reportV2Input = DiagnosticReportV2Input(
                recentEvents = listOf(event),
                flushComplete = true,
                rejectedWrites = 0,
                evictedMemoryEvents = 0,
                ioFailures = 0,
                captureRotations = 0,
            ),
            includeJsonl = true,
        ).readText()
        assertTrue(export.contains("[metadata]"))
        assertTrue(export.contains("[persisted-capture]"))
        assertTrue(export.contains("[last-crash]"))
        assertTrue(export.contains("[report-v2]"))
        assertTrue(export.contains("unique_events=1"))
        assertTrue(export.contains("snapshot_capture_overlap=1"))
        assertTrue(export.contains("[events-jsonl-v2]"))
        assertEquals(1, export.substringAfter("[events-jsonl-v2]").lineSequence()
            .count { it.startsWith("{") })
        assertFalse(export.contains("private.invalid"))
        assertFalse(export.contains("secret"))
    }

    @Test fun realCaptureRotationCounterReflectsKnownEventsOnly() {
        val store = DiagnosticFileStore(temp.newFolder(), 10L)
        store.appendCapture("first")
        store.appendCapture("second")
        assertEquals(1L, store.captureRotationsSinceOpen)
        assertEquals(2, store.captureFiles().size)
    }
    @Test fun legacyCaptureAndCrashCannotLeakPreSanitizerSecretsInSharedTxt() {
        val store = DiagnosticFileStore(temp.newFolder(), 4096L)
        val privateId = "dd51b6f2-2a2a-4f7b-aa8f-a8aa0136a70e"
        val rawUrl = "https://private.example.test/rest/stream?token=super-private"
        store.appendCapture("legacy provider failed: $rawUrl recordingId=$privateId")
        store.writeLastCrash("fatal: Authorization: Bearer older-secret $rawUrl")
        val report = store.createExport(
            metadataLines = listOf("lastEndpoint=$rawUrl"),
            recentEventLines = listOf("legacy audio id=$privateId"),
            fileName = "privacy.txt",
            reportV2Input = DiagnosticReportV2Input(
                recentEvents = emptyList(),
                flushComplete = true,
                rejectedWrites = 0L,
                evictedMemoryEvents = 0L,
            ),
            includeJsonl = true,
        ).readText()
        assertTrue(report.contains("[persisted-capture]"))
        assertTrue(report.contains("[last-crash]"))
        assertTrue(report.contains("[report-v2]"))
        listOf("private.example.test", "super-private", privateId, "older-secret")
            .forEach { assertFalse("export leaked $it", report.contains(it)) }
        assertTrue(report.contains("<uri>"))
        assertTrue(report.contains("unparsed_legacy_capture_lines="))
    }

}
