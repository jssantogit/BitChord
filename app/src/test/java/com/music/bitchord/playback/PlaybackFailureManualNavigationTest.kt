package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackFailureManualNavigationTest {

    @Test
    fun `manual next resets failure cascade without rewriting queue order`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")
        cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL)
        val before = queue.toList()

        cascade.onExplicitUserIntent()

        assertFalse(cascade.isActive)
        assertEquals(before, queue)
        // The policy does not consume or replace the next entry; transport can
        // continue to the same next physical index selected by the user.
        assertEquals(2, queue[2].index)
    }

    @Test
    fun `manual previous resets failure cascade without rewriting queue order`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")
        cascade.onFailure(queue[1], queue, FailureRepeatMode.ALL)
        val before = queue.toList()

        cascade.onExplicitUserIntent()

        assertFalse(cascade.isActive)
        assertEquals(before, queue)
        // Previous remains the existing physical predecessor; failure policy
        // has no separate navigation or queue-edit path.
        assertEquals(0, queue[0].index)
    }

    private fun entries(vararg keys: String): List<FailureQueueEntry> =
        keys.mapIndexed { index, key -> FailureQueueEntry(key, index) }
}
