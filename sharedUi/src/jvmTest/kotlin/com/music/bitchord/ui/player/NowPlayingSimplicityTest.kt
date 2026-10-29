package com.music.bitchord.ui.player

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSimplicityTest {
    private fun track(id: String, tier: QueueTier = QueueTier.CONTEXT) =
        Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, queueTier = tier)

    @Test fun queuePresentationStartsWithCurrentAndUpcomingNotPreviouslyPlayed() {
        val items = listOf(track("past"), track("now"), track("up-next", QueueTier.USER_QUEUE),
            track("next"), track("suggested", QueueTier.AUTOPLAY))
        val tracks = visibleNowPlayingQueue(items, currentIndex = 1)
        assertEquals("now", tracks.nowPlaying?.song?.videoId)
        assertTrue(tracks.history.isEmpty())
        assertEquals(listOf("up-next"), tracks.user.map { it.song.videoId })
        assertEquals(listOf("next"), tracks.context.map { it.song.videoId })
        assertEquals(listOf("suggested"), tracks.autoplay.map { it.song.videoId })
    }

    @Test fun minimalPlayerChromeOmitsStaticOriginsDeviceNamesAndTechnicalStats() {
        assertFalse(NowPlayingChrome.showPlaybackOrigin)
        assertFalse(NowPlayingChrome.showOutputCaption)
        assertFalse(NowPlayingChrome.showTechnicalOverlay)
        assertFalse(NowPlayingChrome.showQueueHistory)
        assertTrue(NowPlayingChrome.showPlaybackTransport)
        assertTrue(NowPlayingChrome.showOutputSelector)
    }
}
