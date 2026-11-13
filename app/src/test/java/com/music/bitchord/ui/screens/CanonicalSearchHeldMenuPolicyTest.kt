package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchHeldMenuPolicyTest {
    private fun result(kind: CanonicalSearchKind, audio: Boolean) = CanonicalCatalogSearchRow(
        musicBrainzId = "test-mbid",
        kind = kind,
        title = "Result",
        subtitle = null,
        hasCatalogedAsset = audio,
    )

    @Test fun metadataOnlyRecordingsHaveNoQueueAction() {
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RECORDING, false)))
    }

    @Test fun nonRecordingsNeverGetQueueAction() {
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.ARTIST, true)))
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RELEASE, true)))
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RELEASE_GROUP, true)))
    }

    @Test fun searchNeverOffersQueueEvenWhenAnAssetIsCataloged() {
        assertFalse(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RECORDING, true)))
    }

    @Test fun searchOffersMetadataSaveForRecordingsAndReleasesButNotArtists() {
        assertTrue(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.RECORDING))
        assertTrue(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.RELEASE))
        assertTrue(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.RELEASE_GROUP))
        assertFalse(canonicalSearchHeldCanAddToPlaylist(CanonicalSearchKind.ARTIST))
    }

    @Test fun downloadActionNeverClaimsMusicBrainzMetadataIsAudio() {
        assertTrue(canonicalSearchHeldShowsDownload(CanonicalSearchKind.RECORDING))
        assertTrue(canonicalSearchHeldShowsDownload(CanonicalSearchKind.RELEASE))
        assertTrue(canonicalSearchHeldShowsDownload(CanonicalSearchKind.RELEASE_GROUP))
        assertFalse(canonicalSearchHeldShowsDownload(CanonicalSearchKind.ARTIST))
    }
}
