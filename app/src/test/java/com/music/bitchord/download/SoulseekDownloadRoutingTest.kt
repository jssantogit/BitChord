package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

/** Issue #172: every acquisition intent must be explicitly manual, no YT fallback. */
class SoulseekDownloadRoutingTest {
    @Test fun oneTrackOpensManualSoulseekSearch() {
        assertEquals(
            SoulseekDownloadRouting.Decision.ManualSearch("NewJeans - OMG"),
            SoulseekDownloadRouting.route("NewJeans", "OMG", 1, true)
        )
    }

    @Test fun releaseAndUnavailablePilotFailClosed() {
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "Track", 1, false)
        )
    }

    @Test fun albumAndPlaylistNeverStartBulkDownloads() {
        for (count in listOf(0, 2, 40)) {
            assertEquals(
                SoulseekDownloadRouting.Decision.BulkNotSupported,
                SoulseekDownloadRouting.route("Artist", "Track", count, true)
            )
        }
    }

    @Test fun malformedOrEmptyIdentityDoesNotReachP2P() {
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("", "", 1, true)
        )
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "\u0000", 1, true)
        )
    }

    @Test fun queryIsSanitizedAndBounded() {
        val query = SoulseekDownloadRouting.route(
            "A".repeat(400), "Song", 1, true
        ) as SoulseekDownloadRouting.Decision.ManualSearch
        assertTrue(query.query.length <= 120)
        assertTrue(query.query.contains("Song"))
        assertEquals(
            SoulseekDownloadRouting.Decision.ManualSearch("Artist - Track"),
            SoulseekDownloadRouting.route("Artist\n", "Track", 1, true)
        )
    }
}
