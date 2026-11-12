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
    @Test fun continuationOriginUsesQueuedContextAndNotCurrentOrManualEntries() {
        val current = song("current").copy(albumName = "Wrong current album")
        val context = song("next").copy(albumName = "New Jeans EP")
        val fromAlbum = queueTracksForPresentation(listOf(current, context), 0, appleStyle = true)
        assertEquals("New Jeans EP", appleQueueContextOrigin(fromAlbum))

        val manual = queueTracksForPresentation(
            listOf(current, song("manual", QueueTier.USER_QUEUE), context), 0, appleStyle = true,
        )
        assertEquals(null, appleQueueContextOrigin(manual))

        val autoOnly = queueTracksForPresentation(
            listOf(current, song("auto", QueueTier.AUTOPLAY)), 0, appleStyle = true,
        )
        assertEquals(null, appleQueueContextOrigin(autoOnly))
    }

    @Test fun contextualAlbumCanUseCurrentPlaybackOriginWhenFutureRowsOmitIt() {
        val current = song("current").copy(albumName = "New Jeans", playbackSource = "New Jeans 1st EP")
        val next = song("next")
        assertEquals(
            "New Jeans 1st EP",
            appleQueueContextOrigin(queueTracksForPresentation(listOf(current, next), 0, appleStyle = true)),
        )
        val differentArtist = next.copy(artist = "Another artist")
        assertEquals(
            null,
            appleQueueContextOrigin(queueTracksForPresentation(listOf(current, differentArtist), 0, appleStyle = true)),
        )
    }

    @Test fun emptyQueueIsIndependentOfCurrentTrackAndHistory() {
        val lone = queueTracksForPresentation(listOf(song("current")), 0, appleStyle = true)
        assertTrue(appleQueueShouldShowEmpty(lone))
        assertFalse(appleQueueHasUpcoming(lone))
        val history = queueTracksForPresentation(listOf(song("past"), song("current")), 1, appleStyle = true)
        assertTrue(appleQueueShouldShowEmpty(history))
        val pending = queueTracksForPresentation(listOf(song("current"), song("next")), 0, appleStyle = true)
        assertFalse(appleQueueShouldShowEmpty(pending))
    }

    @Test fun appleSwipeRemovalAcceptsBothDirectionsAndRejectsShortDrags() {
        assertFalse(appleQueueShouldCommitSwipe(-71f, 72f))
        assertFalse(appleQueueShouldCommitSwipe(71f, 72f))
        assertTrue(appleQueueShouldCommitSwipe(-72f, 72f))
        assertTrue(appleQueueShouldCommitSwipe(72f, 72f))
        assertFalse(appleQueueShouldCommitSwipe(999f, 0f))
    }
}
