package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Missing artist photography is an intentional compact color-only state. */
class NativeArtistBackdropAvailabilityTest {
    @Test fun absentPhotoDoesNotCreateAnEmptyImagePainterOrBlurredDuplicate() {
        assertFalse(hasNativeArtistBackdropStill(null))
        assertFalse(hasNativeArtistBackdropStill(""))
        assertFalse(hasNativeArtistBackdropStill("   "))
    }

    @Test fun verifiedPhotoStillUsesTheExistingBackdropAndColorWash() {
        assertTrue(hasNativeArtistBackdropStill("https://images.example/artist.jpg"))
        assertTrue(hasNativeArtistBackdropStill("content://media/verified-artist"))
    }
}
