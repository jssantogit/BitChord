package com.music.bitchord.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralRatingActionPlateTest {
    @Test fun ratingPlateNeverUsesArtworkEvenWhenArtworkIsAvailable() {
        assertFalse(neutralRatingPlateUsesArtwork(NativeActionCardTreatment.NEUTRAL_FROSTED))
        assertTrue(neutralRatingPlateUsesArtwork(NativeActionCardTreatment.FROSTED))
        assertFalse(neutralRatingPlateUsesArtwork(NativeActionCardTreatment.SOLID))
    }

    @Test fun ratingHasNoOutlineWhileOtherContextCardsKeepTheirOwnStyles() {
        assertNull(nativeContextPlateBorder(NativeActionCardTreatment.NEUTRAL_FROSTED, dark = true))
        assertNull(nativeContextPlateBorder(NativeActionCardTreatment.NEUTRAL_FROSTED, dark = false))
        assertNotNull(nativeContextPlateBorder(NativeActionCardTreatment.FROSTED, dark = true))
        assertNotNull(nativeContextPlateBorder(NativeActionCardTreatment.SOLID, dark = false))
    }

    @Test fun ratingPlatePreservesTheSharedOpaqueNeutralBase() {
        assertEquals(Color(0xFF29292D), nativeContextPlateColor(dark = true))
        assertEquals(Color(0xFFF5F5F7), nativeContextPlateColor(dark = false))
        assertEquals(1f, nativeContextPlateColor(dark = true).alpha)
        // The very same shared plate used in native expanding popups.
        assertEquals(nativeCompactPlateColor(isDark = true, artworkElevation = null),
            nativeContextPlateColor(dark = true))
        assertEquals(nativeCompactPlateColor(isDark = false, artworkElevation = null),
            nativeContextPlateColor(dark = false))
        assertEquals(30, nativeContextPlateCornerRadiusDp(NativeActionCardTreatment.NEUTRAL_FROSTED))
        assertEquals(18, nativeContextPlateCornerRadiusDp(NativeActionCardTreatment.FROSTED))
        assertEquals(18, nativeContextPlateCornerRadiusDp(NativeActionCardTreatment.SOLID))
    }
}
