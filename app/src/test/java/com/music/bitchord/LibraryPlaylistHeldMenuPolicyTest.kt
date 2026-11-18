package com.music.bitchord

import com.music.bitchord.ui.screens.libraryPlaylistUsesHeldContextMenu
import com.music.bitchord.ui.screens.libraryPlaylistHeldPresentation
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
    @Test fun heldPlaylistMaintainsNativeMenuRowsOnDismiss() {
        assertTrue(libraryPlaylistHeldPresentation(
            heldNow = true, liveMenuVisible = true, heldBeforeExit = false,
        ))
        assertTrue(libraryPlaylistHeldPresentation(
            heldNow = false, liveMenuVisible = false, heldBeforeExit = true,
        ))
        assertFalse(libraryPlaylistHeldPresentation(
            heldNow = false, liveMenuVisible = true, heldBeforeExit = true,
        ))
        assertFalse(libraryPlaylistHeldPresentation(
            heldNow = false, liveMenuVisible = false, heldBeforeExit = false,
        ))
    }
}
