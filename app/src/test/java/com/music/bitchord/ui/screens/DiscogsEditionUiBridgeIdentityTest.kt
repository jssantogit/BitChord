package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression for fast album/artist navigation while asynchronous lookups finish. */
class DiscogsEditionUiBridgeIdentityTest {
    private val first = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val second = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"

    @Test fun lastEditionEvidenceMustNotFlashWhenAnotherReleaseBecomesVisible() {
        val previous = DiscogsBoundReleaseEvidence(
            requestedMbid = first,
            evidence = DiscogsEditionEnrichment.Candidate,
        )
        assertEquals(DiscogsEditionEnrichment.Candidate,
            discogsEvidenceForVisibleRelease(first, enabled = true, previous))
        assertNull(discogsEvidenceForVisibleRelease(second, enabled = true, previous))
        assertNull(discogsEvidenceForVisibleRelease(null, enabled = true, previous))
        assertNull(discogsEvidenceForVisibleRelease(first, enabled = false, previous))
    }

    @Test fun localLibraryEditionIdentityNeverLeaksIntoAnotherAlbumOrClosedDetail() {
        val previous = DiscogsBoundLibraryIdentity(
            requestedReleaseId = "local-album-one",
            releaseMbid = first,
        )
        assertEquals(first, discogsMbidForVisibleLibraryRelease("local-album-one", previous))
        assertNull(discogsMbidForVisibleLibraryRelease("local-album-two", previous))
        assertNull(discogsMbidForVisibleLibraryRelease(null, previous))
        assertNull(discogsMbidForVisibleLibraryRelease("", previous))
    }

    @Test fun missingMbidIsARealEmptyObservationNotThePreviousAlbumValue() {
        val missing = DiscogsBoundLibraryIdentity("local-album-one", null)
        assertNull(discogsMbidForVisibleLibraryRelease("local-album-one", missing))
    }
}
