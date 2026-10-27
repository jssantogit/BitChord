package com.music.bitchord.playback

/**
 * Tracks the audible ExoPlayer independently of the removed DSP filters.
 *
 * Each sink captures only its permanent deck identity, while the session
 * moves between decks on Crossfade/Automix and version handoffs. Only the
 * current deck publishes pipeline telemetry. A renderer rebuild creates
 * a new A/B pair and restores A to the audible role.
 */
internal class AudioDeckOwnership {
    // Updated by Media3's session thread and observed by both audio sink
    // threads; the audible role must be visible without synchronization or
    // locking on the real-time playback path.
    @Volatile
    private var activeDeckA = true

    fun isAudible(deckA: Boolean): Boolean = deckA == activeDeckA

    fun handoff() {
        activeDeckA = !activeDeckA
    }

    fun reset() {
        activeDeckA = true
    }
}
