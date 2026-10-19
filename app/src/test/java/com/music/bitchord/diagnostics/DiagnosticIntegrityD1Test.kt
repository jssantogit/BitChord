package com.music.bitchord.diagnostics

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIntegrityD1Test {
    private fun event(
        epoch: Long = 1_700_000_000_000,
        monotonic: Long = 123L,
        sequence: Long = 2L,
        metadata: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        epochMillis = epoch,
        monotonicNanos = monotonic,
        sessionId = "session-fixed",
        level = DiagnosticLevel.WARN,
        category = DiagnosticCategory.NETWORK,
        name = "network_retry",
        message = "line-one\nline-two",
        threadName = "test-thread",
        durationNanos = null,
        metadata = metadata,
        error = null,
        sequence = sequence,
    )

    @Test fun versionedEventLinesExposeUtcMonotonicAndSequenceWithoutMultiline() {
        val formatted = DiagnosticFormatter.format(event())
        assertTrue(formatted.contains("schema=v2"))
        assertTrue(formatted.contains("utc=2023-11-14T22:13:20Z"))
        assertTrue(formatted.contains("seq=2"))
        assertTrue(formatted.contains("mono_ns=123"))
        assertTrue(formatted.contains("WARN NETWORK network_retry"))
        assertFalse(formatted.contains('\n'))
        assertTrue(formatted.contains("line-one\\nline-two"))
    }

    @Test fun sortingBySequenceIsStableAcrossClockRollback() {
        val buffer = DiagnosticBuffer(4)
        buffer.append(event(epoch = 5_000L, monotonic = 500L, sequence = 1L))
        buffer.append(event(epoch = 2_000L, monotonic = 100L, sequence = 2L))
        assertEquals(listOf(1L, 2L), buffer.snapshot().map { it.sequence })
        assertEquals(listOf(5_000L, 2_000L), buffer.snapshot().map { it.epochMillis })
    }

    @Test fun metadataAndEventNameScrubUntrustedUrlsIdsAndNewlines() {
        val uuid = "dd51b6f2-2a2a-4f7b-aa8f-a8aa0136a70e"
        val url = "https://private.invalid/stream?id=$uuid&token=SECRET-MEDIA"
        val fields = DiagnosticSanitizer.metadata(
            mapOf("detail" to "retry\n$url", "mediaId" to uuid, "operation" to "offline_online"),
        )
        assertFalse(fields.toString().contains(uuid))
        assertFalse(fields.toString().contains("private.invalid"))
        assertFalse(fields.toString().contains("SECRET-MEDIA"))
        assertFalse(fields.toString().contains('\n'))
        assertEquals("offline_online", fields["operation"])
        val formatted = DiagnosticFormatter.format(event(metadata = mapOf("detail" to url)))
        assertFalse(formatted.contains(uuid))
        assertFalse(formatted.contains("private.invalid"))
    }

    @Test fun flushCompletenessMustBeRecordedWithoutInventedPersistenceGuarantees() {
        assertEquals("complete", DiagnosticCaptureIntegrity.flushStatus(flushed = true))
        assertEquals("partial_flush_timeout", DiagnosticCaptureIntegrity.flushStatus(flushed = false))
    }
}
