package com.music.bitchord.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Player and song-menu feedback belongs to the canonical Recording, never a
 * legacy provider's thumbs/like mechanism. Album, radio, and playlist actions
 * are not ratings and may continue to be surfaced independently. */
internal enum class TrackFeedbackAction { CANONICAL_STARS }
internal object TrackFeedbackPolicy {
    val visibleActions = setOf(TrackFeedbackAction.CANONICAL_STARS)
}

/**
 * Sharing is based on human-readable music metadata while a provider-neutral
 * recording deep link is not yet available. Never invent an external URL.
 */
internal fun musicOnlyShareText(title: String, artist: String): String {
    val track = title.trim()
    val performer = artist.trim()
    return if (performer.isEmpty()) track else "$track — $performer"
}

/** Static album artwork only. Old stored Canvas preferences cannot silently
 * reactivate video decoders or remote clip requests after the UI removed them. */
internal object MusicOnlyArtwork {
    val animatedArtworkEnabled = MutableStateFlow(false).asStateFlow()
}
