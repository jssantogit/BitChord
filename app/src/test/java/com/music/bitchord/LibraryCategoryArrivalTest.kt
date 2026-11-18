package com.music.bitchord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCategoryArrivalTest {
    @Test fun enteringCategoryDoesNotCrossfadeOverLibraryLanding() {
        assertTrue(libraryCategoryRequiresImmediateExit("tab_3", "unified_library"))
    }

    @Test fun returningFromCategoryDoesNotLeaveAnOldCategoryUnderLanding() {
        assertTrue(libraryCategoryRequiresImmediateExit("unified_library", "tab_3"))
    }

    @Test fun ordinaryTabSwapsRetainTheirExistingTransition() {
        assertFalse(libraryCategoryRequiresImmediateExit("tab_0", "tab_3"))
    }

    @Test fun playlistAndSettingsNavigationAreNotChangedByCategoryPolicy() {
        assertFalse(libraryCategoryRequiresImmediateExit("canonical_playlist_library", "settings"))
    }
}
