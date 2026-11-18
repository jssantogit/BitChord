package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lifted card and action plate belong to the held item, not the page
 * that happened to be open before selecting it. */
class CanonicalHeldMenuPaletteSourceTest {
    @Test fun rootLibraryHeldMenuUsesHeldArtworkNotAMOLEDPage() {
        assertEquals(
            "https://images.example/say-it.jpg",
            heldLibraryPaletteArtworkSource(
                itemArtwork = "https://images.example/say-it.jpg",
                detailArtwork = null,
            ),
        )
    }

    @Test fun missingItemArtUsesTheActualDetailPageIfPresent() {
        assertEquals(
            "content://media/albumart/42",
            heldLibraryPaletteArtworkSource(" ", "content://media/albumart/42"),
        )
        assertNull(heldLibraryPaletteArtworkSource(null, null))
    }
    @Test fun heldAlbumUsesProvenRecordingArtworkWhenCardUsesIt() {
        assertEquals(
            "content://media/track-cover",
            heldLibraryAlbumArtworkSource(
                releaseArtwork = "https://images.example/other-edition.jpg",
                verifiedRecordingArtwork = "content://media/track-cover",
            ),
        )
        assertEquals(
            "https://images.example/release.jpg",
            heldLibraryAlbumArtworkSource(
                releaseArtwork = "https://images.example/release.jpg",
                verifiedRecordingArtwork = null,
            ),
        )
    }

}
