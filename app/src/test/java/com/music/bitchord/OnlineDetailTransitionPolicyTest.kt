package com.music.bitchord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A departing search/list may not remain visible over a native online detail. */
class OnlineDetailTransitionPolicyTest {
    @Test fun onlineArtistAlbumAndPlaylistReplaceOutgoingSearchWithoutOverlap() {
        assertTrue(onlineDetailRequiresImmediateExit("catalog:ARTIST:artist-id"))
        assertTrue(onlineDetailRequiresImmediateExit("catalog:RELEASE:release-id"))
        assertTrue(onlineDetailRequiresImmediateExit("catalog:RELEASE_GROUP:group-id"))
        assertTrue(onlineDetailRequiresImmediateExit("public_playlist:playlist-id"))
    }

    @Test fun rootTabsAndLocalPagesKeepTheirExistingTransitions() {
        assertFalse(onlineDetailRequiresImmediateExit("tab:search"))
        assertFalse(onlineDetailRequiresImmediateExit("library_artist:local-id"))
        assertFalse(onlineDetailRequiresImmediateExit("settings"))
        assertFalse(onlineDetailRequiresImmediateExit("catalog:RECORDING:recording-id"))
    }
}
