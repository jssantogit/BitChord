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
    // One volatile read atomically identifies renderer generation and audible
    // deck. Old sinks may still reset after an output-route rebuild and must
    // not publish stale telemetry over the new pair.
    //
    // Bits [63..1] = generation; bit 0 = physical deck (0=A, 1=B).
    @Volatile
    private var activeToken = 0L

    /** Captured once by each renderer factory when it is constructed. */
    fun generation(): Long = activeToken ushr 1

    fun isAudible(deckA: Boolean, generation: Long): Boolean {
        val deck = if (deckA) 0L else 1L
        return activeToken == ((generation shl 1) or deck)
    }

    fun handoff() {
        activeToken = activeToken xor 1L
    }

    fun reset() {
        activeToken = ((activeToken ushr 1) + 1L) shl 1
    }
}
