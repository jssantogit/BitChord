package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A saved five-star recording may have a unique local rendition selected by L19. */
class CanonicalBatchProviderGateTest {
    @Test fun uniqueLocalAssetRemainsPlayableWithoutAnyOpenSubsonicAccount() {
        assertTrue(canonicalBatchProviderAllowed("local-mediastore", emptyList()))
    }

    @Test fun localAssetRemainsPlayableWithDifferentRemoteAccountConfigured() {
        assertTrue(canonicalBatchProviderAllowed(
            "local-mediastore", listOf("opensubsonic:new-account")
        ))
    }

    @Test fun remoteAssetMustBelongToCurrentAccount() {
        assertTrue(canonicalBatchProviderAllowed(
            "opensubsonic:new-account", listOf("opensubsonic:new-account")
        ))
        assertFalse(canonicalBatchProviderAllowed(
            "opensubsonic:old-account", listOf("opensubsonic:new-account")
        ))
    }

    @Test fun arbitraryProvidersCannotBypassAccountValidation() {
        assertFalse(canonicalBatchProviderAllowed("youtube", listOf("youtube")))
        assertFalse(canonicalBatchProviderAllowed("fake-remote", listOf("fake-remote")))
    }
}
