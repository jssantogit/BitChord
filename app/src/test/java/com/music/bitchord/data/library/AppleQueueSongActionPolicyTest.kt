package com.music.bitchord.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleQueueSongActionPolicyTest {
    @Test fun onlyTheQueueHeaderGetsQueueSpecificActions() {
        assertFalse(shouldUseAppleQueueSongActions(false, false))
        assertFalse(shouldUseAppleQueueSongActions(false, true))
        assertFalse(shouldUseAppleQueueSongActions(true, false))
        assertTrue(shouldUseAppleQueueSongActions(true, true))
    }
    @Test fun ratingEditorIsNotDuplicatedInsideEitherPlayerOverflowMenu() {
        assertFalse(shouldShowRatingEditorInSongActions(fromPlayer = true))
        assertTrue(shouldShowRatingEditorInSongActions(fromPlayer = false))
    }
}
