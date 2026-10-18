package com.music.bitchord.data.listening

internal enum class ListeningEventType {
    PLAYBACK_STARTED,
    PLAYBACK_COMPLETED,
    PLAYBACK_SKIPPED_EARLY,
    PLAYBACK_SKIPPED_LATE,
    PLAYBACK_INTERRUPTED,
}

/**
 * Why an active playback ended, when the player can distinguish it.
 *
 * Explicit navigation is behavioral intent and can be classified as a skip.
 * Stops caused by errors, generic shutdowns, or unknown reasons are kept out of
 * skip signals so infrastructure failures do not teach the recommendation layer
 * that the listener disliked a recording.
 */
internal enum class PlaybackEndCause {
    ENDED,
    NEXT,
    PREVIOUS,
    MANUAL_CHANGE,
    STOPPED,
    ERROR,
    UNKNOWN,
}
