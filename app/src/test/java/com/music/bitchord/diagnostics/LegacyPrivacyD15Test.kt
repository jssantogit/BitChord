package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPrivacyD15Test {
    @Test fun verboseSearchAndVideoSwitchMessagesAreNotExported() {
        val query = "search query=ultra-secret-band; https://example.org/a?token=secret"
        val video = "video switch to Secret Artist - Hidden Song videoId=abcd1234"
        assertEquals("legacy_payload_suppressed", DiagnosticLegacyPrivacy.message("Search", query))
        assertEquals("legacy_payload_suppressed", DiagnosticLegacyPrivacy.message("VideoSwitch", video))
        assertFalse(DiagnosticLegacyPrivacy.message("Catalog", "title=Private Song").contains("Private Song"))
    }

    @Test fun dynamicTagsCannotContainUserInput() {
        assertEquals("other", DiagnosticLegacyPrivacy.tag("private song by artist"))
        assertEquals("VideoSwitch", DiagnosticLegacyPrivacy.tag("VideoSwitch"))
        assertEquals("other", DiagnosticLegacyPrivacy.tag("Token=hidden"))
        assertEquals("other", DiagnosticLegacyPrivacy.tag("Madonna"))
    }

    @Test fun ordinaryDiagnosticSignalRemainsObservableAndSanitized() {
        val safe = DiagnosticLegacyPrivacy.message("Network", "retry_count=3")
        assertEquals("retry_count=3", safe)
        val uri = DiagnosticLegacyPrivacy.message("Network", "GET https://example.org/private")
        assertFalse(uri.contains("example.org"))
        assertTrue(uri.contains("<uri>"))
    }

    @Test fun ringLossRemainsExplicitAfterSaturation() {
        val ring = DiagnosticBuffer(2)
        repeat(5) { seq ->
            ring.append(DiagnosticEvent(10L + seq, 100L + seq, "session", DiagnosticLevel.INFO,
                DiagnosticCategory.UI, "trace_event", null, "unit", null,
                mapOf("outcome" to "unknown"), null))
        }
        assertEquals(2, ring.snapshot().size)
        assertEquals(3L, ring.evictedCount)
    }
}
