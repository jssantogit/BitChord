package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** First-party artist ratings do not require playable songs. */
class NativeArtistRatingActionVisibilityTest {
    @Test fun metadataOnlyVerifiedArtistKeepsItsStarButNotPlayback() {
        assertTrue(shouldShowNativeCanonicalArtistActions(hasTracks = false, canRate = true))
        assertFalse(shouldShowNativeCanonicalArtistActions(hasTracks = false, canRate = false))
        assertTrue(shouldShowNativeCanonicalArtistActions(hasTracks = true, canRate = false))
    }
}
