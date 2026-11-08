package com.music.bitchord.ui.player

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleQueuePresentationTest {
    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) =
        Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, queueTier = tier)

    @Test fun historyComesOnlyFromPreviouslyPlayedTimeline() {
        val queue = listOf(song("a"), song("b"), song("current"), song("next", QueueTier.USER_QUEUE),
            song("recommendation", QueueTier.AUTOPLAY))
        val apple = queueTracksForPresentation(queue, 2, appleStyle = true)
        val original = queueTracksForPresentation(queue, 2, appleStyle = false)
        assertEquals(listOf("a", "b"), apple.history.map { it.song.videoId })
        assertEquals("current", apple.nowPlaying?.song?.videoId)
        assertEquals(listOf(3), apple.user.map { it.timelineIndex })
        assertEquals(listOf(4), apple.autoplay.map { it.timelineIndex })
        assertTrue(original.history.isEmpty())
    }
    @Test fun removalGestureNeverTouchesHistoryOrLockedQueues() {
        assertTrue(appleQueueMaySwipeRemove(true, false, false, false))
        assertFalse(appleQueueMaySwipeRemove(true, true, false, false))
        assertFalse(appleQueueMaySwipeRemove(true, false, true, false))
        assertFalse(appleQueueMaySwipeRemove(true, false, false, true))
        assertFalse(appleQueueMaySwipeRemove(false, false, false, false))
    }
    @Test fun duplicateRowsRetainDistinctKeysAndIndices() {
        val queue = List(4) { song("duplicate") }
        val apple = queueTracksForPresentation(queue, 2, appleStyle = true)
        val all = apple.history + listOfNotNull(apple.nowPlaying) + apple.context
        assertEquals(4, all.map { it.key }.distinct().size)
        assertEquals(listOf(0, 1, 2, 3), all.map { it.timelineIndex })
    }
}
