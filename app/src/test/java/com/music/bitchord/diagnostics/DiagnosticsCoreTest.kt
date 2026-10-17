package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCoreTest {

    @Test
    fun `bounded buffer keeps the newest events in order`() {
        val buffer = DiagnosticBuffer(capacity = 3)
        repeat(5) { index ->
            buffer.append(event(name = "event-$index", monotonicNanos = index.toLong()))
        }

        assertEquals(listOf("event-2", "event-3", "event-4"), buffer.snapshot().map { it.name })
        assertEquals(3, buffer.size)
    }

    @Test
    fun `clear empties the bounded buffer`() {
        val buffer = DiagnosticBuffer(capacity = 2)
        buffer.append(event(name = "one"))
        buffer.append(event(name = "two"))

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0, buffer.size)
    }

    @Test
    fun `sensitive metadata values are redacted by key`() {
        val sanitized = DiagnosticSanitizer.metadata(
            mapOf(
                "trackId" to "abc123",
                "Authorization" to "Bearer very-secret",
                "cookie" to "SID=secret-cookie",
                "access_token" to "secret-token",
                "accessToken" to "secret-camel-token",
                "apiKey" to "secret-api-key",
                "sessionKey" to "secret-session-key",
                "password" to "secret-password",
            ),
        )

        assertEquals("abc123", sanitized["trackId"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["Authorization"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["cookie"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["access_token"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["accessToken"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["apiKey"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["sessionKey"])
        assertEquals(DiagnosticSanitizer.REDACTED, sanitized["password"])
    }

    @Test
    fun `diagnostic text and metadata are size bounded`() {
        val longText = "x".repeat(DiagnosticSanitizer.MAX_TEXT_CHARS + 100)
        val metadata = (0 until DiagnosticSanitizer.MAX_METADATA_ENTRIES + 5)
            .associate { index -> "key$index" to longText }

        val sanitizedText = DiagnosticSanitizer.text(longText)
        val sanitizedMetadata = DiagnosticSanitizer.metadata(metadata)

        assertEquals(DiagnosticSanitizer.MAX_TEXT_CHARS, sanitizedText.length)
        assertEquals(DiagnosticSanitizer.MAX_METADATA_ENTRIES, sanitizedMetadata.size)
        assertTrue(sanitizedMetadata.values.all { it.length <= DiagnosticSanitizer.MAX_METADATA_VALUE_CHARS })
    }

    @Test
    fun `sensitive inline values and url query parameters are redacted`() {
        val message = "Authorization: Bearer abc Cookie: SID=qwerty https://example.test/play?id=42&token=hunter2&safe=yes"

        val sanitized = DiagnosticSanitizer.text(message)

        assertFalse(sanitized.contains("Bearer abc"))
        assertFalse(sanitized.contains("SID=qwerty"))
        assertFalse(sanitized.contains("hunter2"))
        assertTrue(sanitized.contains("id=42"))
        assertTrue(sanitized.contains("safe=yes"))
        assertTrue(sanitized.contains(DiagnosticSanitizer.REDACTED))
    }

    @Test
    fun `standalone bearer credentials and known cookie values are redacted`() {
        val sanitized = DiagnosticSanitizer.text("request failed Bearer abc.def SID=qwerty safe=value")

        assertFalse(sanitized.contains("abc.def"))
        assertFalse(sanitized.contains("qwerty"))
        assertTrue(sanitized.contains("safe=value"))
    }

    @Test
    fun `capture window is active only before its expiry`() {
        assertTrue(CaptureWindow(expiresAtEpochMillis = 2_000L).isActive(nowEpochMillis = 1_999L))
        assertFalse(CaptureWindow(expiresAtEpochMillis = 2_000L).isActive(nowEpochMillis = 2_000L))
        assertFalse(CaptureWindow(expiresAtEpochMillis = 2_000L).isActive(nowEpochMillis = 2_001L))
    }

    @Test
    fun `formatter includes timing identity and sanitized metadata`() {
        val formatted = DiagnosticFormatter.format(
            event(
                name = "source_registry_init",
                monotonicNanos = 9_000_000L,
                durationNanos = 612_400_000L,
                metadata = mapOf("token" to "secret", "sources" to "4"),
            ),
        )

        assertTrue(formatted.contains("STARTUP"))
        assertTrue(formatted.contains("source_registry_init"))
        assertTrue(formatted.contains("612.4ms"))
        assertTrue(formatted.contains("sources=4"))
        assertFalse(formatted.contains("secret"))
        assertTrue(formatted.contains("token=${DiagnosticSanitizer.REDACTED}"))
    }

    private fun event(
        name: String,
        monotonicNanos: Long = 1L,
        durationNanos: Long? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = DiagnosticEvent(
        epochMillis = 1_700_000_000_000L,
        monotonicNanos = monotonicNanos,
        sessionId = "session-test",
        level = DiagnosticLevel.INFO,
        category = DiagnosticCategory.STARTUP,
        name = name,
        message = null,
        threadName = "test",
        durationNanos = durationNanos,
        metadata = metadata,
        error = null,
    )
}
