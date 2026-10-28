package com.music.bitchord.ui.player

import com.music.bitchord.data.model.Song

/**
 * Presentation contract of the uncluttered Android Now Playing. The underlying
 * recording source, negotiated output and statistics remain available to the
 * playback/diagnostics engine; they are just not persistent chrome.
 */
internal object NowPlayingChrome {
    const val showPlaybackOrigin = false
    const val showOutputCaption = false
    const val showTechnicalOverlay = false
    const val showQueueHistory = false
    const val showPlaybackTransport = true
    const val showOutputSelector = true
}

/** No history above the current song on the main queue surface. */
internal fun visibleNowPlayingQueue(queue: List<Song>, currentIndex: Int): QueueTracks =
    splitQueue(queue, currentIndex, showHistory = NowPlayingChrome.showQueueHistory)
