package com.music.bitchord.playback

/**
 * Logical guard for the existing single-Media3-player EOS boundary.
 * Does not predict buffer/sample continuity on an Android output device.
 */
internal object GaplessBoundaryPolicy {
    fun mustUseNativeBoundary(crossfadeMs: Long, smartFadeEnabled: Boolean): Boolean = false
}
