package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsCanonicalEditionCoordinator
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionAccess
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * BitChord is currently for private, personal Android use.
 * Public distribution is a separate decision; it is not a reason to ship a
 * permanently disabled enrichment feature in the owner's APK.
 */
class DiscogsPersonalAccessContractTest {
    @Test fun personalAppHasReadOnlyDiscogsEnrichmentActiveByDefault() {
        assertTrue("Real edition API flow must be active before physical smoke",
            DiscogsEditionAccess.ENABLED)
    }

    @Test fun activePersonalModeStillRejectsInvalidIdBeforeAnyNetwork() = runBlocking {
        assertEquals(DiscogsEditionEnrichment.Invalid,
            DiscogsEditionAccess.coordinator.observeRelease("not-an-mbid"))
    }

    @Test fun personalUseRetainsOfficialSourceNotice() {
        assertTrue(DiscogsEditionAccess.NON_AFFILIATION_NOTICE
            .contains("not affiliated with, sponsored or endorsed by Discogs"))
        assertTrue(DiscogsEditionAccess.NON_AFFILIATION_NOTICE
            .contains("Zink Media, LLC"))
    }
}
