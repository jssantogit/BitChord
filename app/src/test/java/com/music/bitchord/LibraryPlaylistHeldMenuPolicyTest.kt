package com.music.bitchord

import com.music.bitchord.ui.screens.libraryPlaylistUsesHeldContextMenu
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaylistHeldMenuPolicyTest {
    @Test fun onlyAnActualCapturedHoldWithBackdropOpensLiftedActions() {
        assertTrue(libraryPlaylistUsesHeldContextMenu(
            hasCapturedHold = true, hasHazeBackdrop = true,
        ))
        assertFalse(libraryPlaylistUsesHeldContextMenu(
            hasCapturedHold = false, hasHazeBackdrop = true,
        ))
        assertFalse(libraryPlaylistUsesHeldContextMenu(
            hasCapturedHold = true, hasHazeBackdrop = false,
        ))
    }
}
