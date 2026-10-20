package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureCascadeTest {

    @Test
    fun `remote failure advances to next local entry`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("remote-a", "local-b")

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF),
        )
        assertEquals(setOf("remote-a"), cascade.failedKeys)
    }

    @Test
    fun `remote failure advances to next healthy remote entry`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("remote-a", "remote-b")

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF),
        )
    }

    @Test
    fun `two consecutive failures advance to third entry`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(2),
            cascade.onFailure(queue[1], queue, FailureRepeatMode.OFF),
        )
        assertEquals(setOf("a", "b"), cascade.failedKeys)
    }

    @Test
    fun `all failures exhaust cascade without revisiting an entry`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(2),
            cascade.onFailure(queue[1], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Exhausted,
            cascade.onFailure(queue[2], queue, FailureRepeatMode.ALL),
        )
        assertEquals(setOf("a", "b", "c"), cascade.failedKeys)
    }

    @Test
    fun `repeat one is ignored for failure advance without retrying current entry`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.ONE),
        )
        assertFalse(cascade.failedKeys.contains("b"))
    }

    @Test
    fun `repeat all wraps once but cannot cycle forever`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(0),
            cascade.onFailure(queue[2], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Exhausted,
            cascade.onFailure(queue[1], queue, FailureRepeatMode.ALL),
        )
    }

    @Test
    fun `shuffle follows materialized queue order and deduplicates failures`() {
        val cascade = PlaybackFailureCascade()
        val queue = listOf(
            FailureQueueEntry("a", 0),
            FailureQueueEntry("c", 1),
            FailureQueueEntry("b", 2),
        )

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(2),
            cascade.onFailure(queue[1], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Exhausted,
            cascade.onFailure(queue[2], queue, FailureRepeatMode.ALL),
        )
    }

    @Test
    fun `same logical context entry in a later repeat cycle is attempted once`() {
        val cascade = PlaybackFailureCascade()
        val queue = listOf(
            FailureQueueEntry("context:0", 0),
            FailureQueueEntry("context:1", 1),
            FailureQueueEntry("context:0", 2),
            FailureQueueEntry("context:1", 3),
        )

        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL),
        )
        assertEquals(
            PlaybackFailureCascade.Decision.Exhausted,
            cascade.onFailure(queue[1], queue, FailureRepeatMode.ALL),
        )
    }

    @Test
    fun `successful playback resets cascade`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b")
        cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF)

        cascade.onPlaybackStarted()

        assertFalse(cascade.isActive)
        assertTrue(cascade.failedKeys.isEmpty())
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF),
        )
    }

    @Test
    fun `explicit user navigation resets cascade without changing queue`() {
        val cascade = PlaybackFailureCascade()
        val queue = entries("a", "b", "c")
        cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL)
        val before = queue.toList()

        cascade.onExplicitUserIntent()

        assertFalse(cascade.isActive)
        assertEquals(before, queue)
        assertEquals(
            PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF),
        )
    }

    @Test
    fun `late Navidrome failure after queue changed never poisons current cascade`() {
        val cascade = PlaybackFailureCascade()
        val pendingRemote = FailureQueueEntry("remote-a", 0)
        val updatedQueue = entries("remote-b", "local-viable")
        val originalQueue = updatedQueue.toList()

        assertEquals(PlaybackFailureCascade.Decision.Exhausted,
            cascade.onFailure(pendingRemote, updatedQueue, FailureRepeatMode.ALL))
        assertFalse("stale response must not start a failure cascade", cascade.isActive)
        assertTrue(cascade.failedKeys.isEmpty())
        assertEquals(originalQueue, updatedQueue)
        assertEquals(PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(updatedQueue[0], updatedQueue, FailureRepeatMode.OFF))
    }

    @Test
    fun `Navidrome offline to local handoff and explicit online reselect preserves original queue`() {
        val queue = entries("remote-a", "remote-b", "local-c")
        val snapshot = queue.toList()
        val cascade = PlaybackFailureCascade()

        assertEquals(PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF))
        assertEquals(PlaybackFailureCascade.Decision.Advance(2),
            cascade.onFailure(queue[1], queue, FailureRepeatMode.OFF))
        cascade.onPlaybackStarted()
        assertFalse(cascade.isActive)
        assertEquals(snapshot, queue)

        // An explicit selection once Navidrome reconnects may replay A, but
        // the runtime guard cannot silently change its provider or rendition.
        cascade.onExplicitUserIntent()
        assertTrue(cascade.failedKeys.isEmpty())
        assertEquals(PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.OFF))
        assertEquals(snapshot, queue)
    }

    @Test
    fun `all remote failures exhaust once including repeat all without modifying original entries`() {
        val queue = entries("remote-a", "remote-b")
        val before = queue.toList()
        val cascade = PlaybackFailureCascade()
        assertEquals(PlaybackFailureCascade.Decision.Advance(1),
            cascade.onFailure(queue[0], queue, FailureRepeatMode.ALL))
        assertEquals(PlaybackFailureCascade.Decision.Exhausted,
            cascade.onFailure(queue[1], queue, FailureRepeatMode.ALL))
        assertEquals(before, queue)
        assertEquals(setOf("remote-a", "remote-b"), cascade.failedKeys)
    }

    private fun entries(vararg keys: String): List<FailureQueueEntry> =
        keys.mapIndexed { index, key -> FailureQueueEntry(key, index) }
}
