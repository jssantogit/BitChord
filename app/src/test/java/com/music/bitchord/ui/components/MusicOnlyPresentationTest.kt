package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MusicOnlyPresentationTest {
    @Test fun sharingSongNeverGeneratesProviderOrVideoLink() {
        val shared = musicOnlyShareText("Thriller", "Michael Jackson")
        assertEquals("Thriller — Michael Jackson", shared)
        assertFalse(shared.contains("youtube", ignoreCase = true))
        assertFalse(shared.contains("watch?v=", ignoreCase = true))
    }

    @Test fun sharingWithoutArtistUsesJustTheTitle() {
        assertEquals("Unknown Track", musicOnlyShareText(" Unknown Track ", " "))
    }

    @Test fun storedVideoArtworkPreferenceCannotReactivateMovingCovers() {
        assertFalse(MusicOnlyArtwork.animatedArtworkEnabled.value)
    }

    @Test fun onlyCanonicalStarsMayBePresentedAsTrackRating() {
        assertEquals(setOf(TrackFeedbackAction.CANONICAL_STARS), TrackFeedbackPolicy.visibleActions)
    }
}
