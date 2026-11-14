package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleProminentArtworkGuardTest {
    private val thumb = "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/100x100bb.jpg"

    @Test fun cannotStretchAppleSearchThumbnailIntoLargeEditorialHero() {
        assertNull(appleProminentArtworkOrNull(thumb))
        assertNull(appleProminentArtworkOrNull(thumb.replace("100x100", "480x480")))
    }

    @Test fun displayUsesTheActualVerifiedLargeAppleVariantWhenPublished() {
        val full = thumb.replace("100x100", "1024x1024")
        assertEquals(full, appleProminentArtworkOrNull(full))
        val fallback = thumb.replace("100x100", "600x600")
        assertEquals(fallback, appleProminentArtworkOrNull(fallback))
    }

    @Test fun preservedNonAppleLibraryAndLocalArtworkDoesNotGetHidden() {
        assertEquals("https://example.org/cover.jpg",
            appleProminentArtworkOrNull("https://example.org/cover.jpg"))
        assertNull(appleProminentArtworkOrNull(null))
    }

    @Test fun unrecognizedAppleSizeCannotPretendToBeVerified() {
        assertNull(appleProminentArtworkOrNull(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/abc/art.jpg"))
    }
}
