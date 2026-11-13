package com.music.bitchord.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryHeldContextMenuPolicyTest {
    @Test fun capturedHoldWithBackdropLiftsLibraryItem() {
        assertTrue(canonicalLibraryUsesHeldContextMenu(
            hasCapturedHold = true, hasHazeBackdrop = true,
        ))
    }

    @Test fun toolbarOverflowDoesNotBecomeAContextualLift() {
        assertFalse(canonicalLibraryUsesHeldContextMenu(
            hasCapturedHold = false, hasHazeBackdrop = true,
        ))
    }

    @Test fun missingBackdropUsesExistingSheetFallback() {
        assertFalse(canonicalLibraryUsesHeldContextMenu(
            hasCapturedHold = true, hasHazeBackdrop = false,
        ))
    }
    @Test fun dismissingLiftedRowPreservesCompactActionsDuringExit() {
        assertTrue(canonicalLibraryHeldPresentation(
            heldNow = true, liveMenuVisible = true, heldBeforeExit = false,
        ))
        assertTrue(canonicalLibraryHeldPresentation(
            heldNow = false, liveMenuVisible = false, heldBeforeExit = true,
        ))
    }

    @Test fun normalOverflowAndFreshMenuDoNotInheritStaleLiftStyle() {
        assertFalse(canonicalLibraryHeldPresentation(
            heldNow = false, liveMenuVisible = true, heldBeforeExit = true,
        ))
        assertFalse(canonicalLibraryHeldPresentation(
            heldNow = false, liveMenuVisible = false, heldBeforeExit = false,
        ))
    }
}
