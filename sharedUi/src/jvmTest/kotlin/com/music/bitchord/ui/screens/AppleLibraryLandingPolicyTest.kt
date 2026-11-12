package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleLibraryLandingPolicyTest {
    @Test fun referenceOrderIsCoversThenCategoriesThenPreservedRecentAdditions() {
        assertEquals(
            listOf(AppleLibraryLandingSection.PINS, AppleLibraryLandingSection.CATEGORIES, AppleLibraryLandingSection.RECENT),
            appleLibraryLandingSections(hasPins = true, hasCategories = true, hasRecent = true),
        )
    }

    @Test fun emptyPinsNeverProduceDecorativePlaceholders() {
        assertEquals(
            listOf(AppleLibraryLandingSection.CATEGORIES, AppleLibraryLandingSection.RECENT),
            appleLibraryLandingSections(hasPins = false, hasCategories = true, hasRecent = true),
        )
    }

    @Test fun recentItemsArePreservedEvenWhenNoPinnedCoversExist() {
        assertEquals(
            listOf(AppleLibraryLandingSection.RECENT),
            appleLibraryLandingSections(hasPins = false, hasCategories = false, hasRecent = true),
        )
    }
}
