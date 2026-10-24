package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

/** Issue #172: acquisition is an explicit manual decision, never a YT fallback. */
class SoulseekDownloadRoutingTest {
    @Test fun oneTrackOnDevDebugOpensSoulseekManualSearch() {
        val result = SoulseekDownloadRouting.route(
            artist = "NewJeans", title = "OMG", count = 1, pilotAvailable = true
        )
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("NewJeans - OMG"), result)
    }

    @Test fun releaseBuildFailsClosed() {
        val result = SoulseekDownloadRouting.route(
            artist = "Artist", title = "Track", count = 1, pilotAvailable = false
        )
        assertEquals(SoulseekDownloadRouting.Decision.Unavailable, result)
    }

    @Test fun bulkNeverQueuesAutomatically() {
        val result = SoulseekDownloadRouting.route(
            artist = "Artist", title = "Track", count = 40, pilotAvailable = true
        )
        assertEquals(SoulseekDownloadRouting.Decision.BulkNotSupported, result)
    }

    @Test fun invalidTrackIdentityDoesNotProduceNetworkQuery() {
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("", "", 1, true)
        )
        assertEquals(
            SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("Artist", "\u0000", 1, true)
        )
    }

    @Test fun manualSearchQueryIsBoundedAndClean() {
        val long = "A".repeat(400)
        val result = SoulseekDownloadRouting.route(long, "Song", 1, true)
        assertTrue(result is SoulseekDownloadRouting.Decision.ManualSearch)
        val query = (result as SoulseekDownloadRouting.Decision.ManualSearch).query
        assertTrue(query.length <= 120)
        assertTrue(query.contains("Song"))
    }
}
