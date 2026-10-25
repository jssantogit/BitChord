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

    @Test fun musicPlaceholderIsNeverSentAsAnArtist() {
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("Magnetic"),
            SoulseekDownloadRouting.route("Música", "Magnetic", 1, true))
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("Magnetic"),
            SoulseekDownloadRouting.route("Music", "Magnetic", 1, true))
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("Magnetic"),
            SoulseekDownloadRouting.route("Unknown Artist", "Magnetic", 1, true))
    }

    @Test fun preferRealCreditedArtistOverGenericDisplayCategory() {
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("ILLIT - Magnetic"),
            SoulseekDownloadRouting.route(
                "Música", "Magnetic", 1, true,
                creditedArtists=listOf("ILLIT")
            ))
    }

    @Test fun missingArtistStillAllowsManualTitleOnlySearchWithoutInventingCredit() {
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("Magnetic"),
            SoulseekDownloadRouting.route("", "Magnetic", 1, true))
        assertEquals(SoulseekDownloadRouting.Decision.Unavailable,
            SoulseekDownloadRouting.route("ILLIT", "", 1, true))
        assertEquals(SoulseekDownloadRouting.Decision.ManualSearch("ILLIT - Magnetic"),
            SoulseekDownloadRouting.route("ILLIT", "Magnetic", 1, true))
    }

    @Test fun durationHintIsBoundedAndNeverMistakenForArtist() {
        assertEquals(161,SoulseekDownloadRouting.durationSeconds("2:41"))
        assertEquals(3661,SoulseekDownloadRouting.durationSeconds("1:01:01"))
        assertNull(SoulseekDownloadRouting.durationSeconds("unknown"))
        assertNull(SoulseekDownloadRouting.durationSeconds("999:00"))
        assertNull(SoulseekDownloadRouting.durationSeconds("0:03"))
    }

    @Test fun preparedManualSearchKeepsUnverifiedAlbumWithoutInventingMbids() {
        val prepared = SoulseekDownloadRouting.prepareManual(
            artist = "Música", title = "Magnetic", count = 1,
            pilotAvailable = true, creditedArtists = listOf("ILLIT"),
            requestedAlbum = "Super Real Me", expectedDurationSeconds = 161,
        )
        assertNotNull(prepared)
        assertEquals("ILLIT - Magnetic", prepared!!.query)
        assertEquals("Super Real Me", prepared.snapshot.requestedAlbum)
        assertEquals(listOf("ILLIT"), prepared.snapshot.artistCredits)
        assertNull(prepared.snapshot.ids)
        assertEquals(161, prepared.snapshot.expectedDurationSeconds)
        assertEquals(
            com.music.bitchord.acquisition.a0.A0CanonicalAcquisitionIntent.Origin.MANUAL_QUERY,
            prepared.snapshot.origin,
        )
        assertFalse(prepared.toString().contains("ILLIT"))
    }

    @Test fun bulkOrUnavailableFlowCannotCreatePreparedManualContext() {
        assertNull(SoulseekDownloadRouting.prepareManual(
            artist = "ILLIT", title = "Magnetic", count = 5, pilotAvailable = true,
        ))
        assertNull(SoulseekDownloadRouting.prepareManual(
            artist = "ILLIT", title = "Magnetic", count = 1, pilotAvailable = false,
        ))
    }

}
