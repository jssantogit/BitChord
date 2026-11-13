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

    @Test fun catalogedRecordingHasQueueAction() {
        assertTrue(canonicalSearchHeldQueueAllowed(result(CanonicalSearchKind.RECORDING, true)))
    }
}
