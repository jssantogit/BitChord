package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The disabled Radio slot must never steal Library/Search route indices. */
class AppleMusicBottomBarPolicyTest {
    @Test
    fun fiveVisualSlotsOnlyExposeFourExistingDestinations() {
        assertEquals(
            listOf(0, 1, null, 2, 3),
            (0..4).map(::appleMusicDestinationForVisualIndex),
        )
    }

    @Test
    fun radioIsDisabledNotMappedToLibraryOrSearch() {
        assertNull(appleMusicDestinationForVisualIndex(2))
        assertNull(appleMusicDestinationForVisualIndex(-1))
        assertNull(appleMusicDestinationForVisualIndex(5))
    }

    @Test
    fun libraryAndSearchSelectionAppearAfterRadio() {
        assertEquals(
            listOf(0, 1, 3, 4),
            (0..3).map(::appleMusicVisualIndexForDestination),
        )
        for (destination in 0..3) {
            assertEquals(
                destination,
                appleMusicDestinationForVisualIndex(
                    appleMusicVisualIndexForDestination(destination),
                ),
            )
        }
    }
}
