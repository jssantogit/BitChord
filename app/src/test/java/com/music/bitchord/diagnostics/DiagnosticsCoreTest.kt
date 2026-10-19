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
    fun `free text hides canonical identity and authenticated urls but preserves typed trace ids`() {
        val rawId = "0b5f7683-2bea-4e4c-8ae0-ce0797afd50e"
        val rawUri = "bitchord://canonical?recordingId=$rawId&mediaAssetId=$rawId&providerKey=opensubsonic%3A$rawId"
        val rawUrl = "https://audio.invalid/rest/stream.view?id=private-track&token=secret-value"
        val message = "no embedded lyrics in $rawUri; links:$rawId failed; upstream=$rawUrl"
        val sanitized = DiagnosticSanitizer.freeText(message)

        assertFalse(sanitized.contains(rawId))
        assertFalse(sanitized.contains("bitchord://canonical"))
        assertFalse(sanitized.contains("audio.invalid"))
        assertFalse(sanitized.contains("private-track"))
        assertFalse(sanitized.contains("secret-value"))
        assertTrue(sanitized.contains("no embedded lyrics"))
        assertTrue(sanitized.contains("links:"))

        // The export formatter is the final persisted-capture / Share boundary,
        // including legacy WARN and throwable text, not just a standalone helper.
        val exported = DiagnosticFormatter.format(
            event(name = "debug_log:BitChord").copy(
                level = DiagnosticLevel.WARN,
                message = message,
                error = message,
            ),
        )
        assertFalse(exported.contains(rawId))
        assertFalse(exported.contains("bitchord://canonical"))
        assertFalse(exported.contains("audio.invalid"))
        assertTrue(exported.contains("no embedded lyrics"))

        val structured = DiagnosticFormatter.format(
            event(name = "trace_event", metadata = mapOf("traceId" to rawId)),
        )
        assertTrue(structured.contains("traceId=$rawId"))
    }

    @Test
    fun `verbose legacy text redacts opaque provider media ids without removing useful context`() {
        val videoOne = "AbCD12_-xYz"
        val videoTwo = "XYz_9876-Ab"
        val albumId = "9876543210"
        val raw = "radio:$videoOne ok; links:$videoTwo ok; motion artwork on album 'EXAMPLE - EP' ($albumId); home:more ok"
        val result = DiagnosticSanitizer.freeText(raw)

        assertFalse(result.contains(videoOne))
        assertFalse(result.contains(videoTwo))
        assertFalse(result.contains(albumId))
        assertTrue(result.contains("radio:<id> ok"))
        assertTrue(result.contains("links:<id> ok"))
        assertTrue(result.contains("motion artwork on album 'EXAMPLE - EP' (<id>)"))
        assertTrue(result.contains("home:more ok"))

        val exported = DiagnosticFormatter.format(
            event(name = "debug_log:BitChord").copy(
                level = DiagnosticLevel.DEBUG,
                category = DiagnosticCategory.OTHER,
                message = raw,
            ),
        )
        assertFalse(exported.contains(videoOne))
        assertFalse(exported.contains(videoTwo))
        assertFalse(exported.contains(albumId))
        assertTrue(exported.contains("motion artwork on album"))
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
