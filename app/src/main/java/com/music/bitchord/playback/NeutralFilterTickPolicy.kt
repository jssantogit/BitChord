package com.music.bitchord.playback

/**
 * Audio analysis and Crossfade gain automation still run on every transition.
 * Only DSP-filter lead calculations and filter rides are unnecessary when the
 * Android sink uses the intentionally absent [TransitionFilters.None] port.
 */
internal object NeutralFilterTickPolicy {
    fun shouldCompute(filters: TransitionFilters): Boolean = filters !== TransitionFilters.None
}
