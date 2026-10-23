package com.music.bitchord.diagnostics

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Regression for the real legacy DebugLog -> Diagnostics -> export path. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LegacyDebugLogErrorD15Test {
    @Test fun throwableMediaFieldsCannotEscapeThroughExportedError() {
        val privateSong = "Private Song Unique D15"
        val privateArtist = "Private Artist Unique D15"
        Diagnostics.fromDebugLog(
            'E', "Network", "retry_count=3",
            IllegalStateException(
                "title=$privateSong; artist=$privateArtist; " +
                    "GET https://example.org/private?token=secret"
            ),
        )
        val event = Diagnostics.recentEvents().last {
            it.name == "debug_log:Network"
        }
        val export = DiagnosticFormatter.format(event)
        assertTrue("Keep diagnostic exception type", export.contains("IllegalStateException"))
        assertTrue("Keep safe diagnostic message", export.contains("retry_count=3"))
        assertFalse("Do not leak media title", export.contains(privateSong))
        assertFalse("Do not leak media artist", export.contains(privateArtist))
        assertFalse("Do not leak URL", export.contains("example.org"))
        assertFalse("Do not leak token", export.contains("token=secret"))
        assertTrue("Error still provides stack signal", event.error?.contains("LegacyDebugLogErrorD15Test") == true)
    }

    @Test fun sensitiveSearchExceptionDoesNotExposeUnlabeledSearchText() {
        val privateQuery = "Unique Unlabeled Search Target D15"
        Diagnostics.fromDebugLog(
            'E', "Search", "search query=hidden",
            IllegalArgumentException("failed query: $privateQuery"),
        )
        val event = Diagnostics.recentEvents().last {
            it.name == "debug_log:Search"
        }
        val export = DiagnosticFormatter.format(event)
        assertFalse(export.contains(privateQuery))
        assertTrue(export.contains("legacy_payload_suppressed"))
    }
}
