package com.music.bitchord.data.scrobbling

/**
 * Measures time spent in Media3's isPlaying state with a monotonic clock.
 * Wall-clock time since the first play may include hours paused and must not
 * be used as proof that a recording has been listened to.
 *
 * One instance tracks one queue occurrence, not one Recording MBID: repeat-one
 * starts a fresh session. A seek/skip is not counted as playback time.
 */
internal class ListenBrainzPlaybackClock {
    private var activeSinceMs: Long? = null
    private var completedMs = 0L

    fun onPlay(monotonicNowMs: Long) {
        if (monotonicNowMs >= 0L && activeSinceMs == null) {
            activeSinceMs = monotonicNowMs
        }
    }

    fun onPause(monotonicNowMs: Long) {
        val started = activeSinceMs ?: return
        activeSinceMs = null
        if (monotonicNowMs >= started) {
            completedMs = safePlus(completedMs, monotonicNowMs - started)
        }
    }

    fun listenedMs(monotonicNowMs: Long): Long {
        val started = activeSinceMs ?: return completedMs
        return if (monotonicNowMs >= started) {
            safePlus(completedMs, monotonicNowMs - started)
        } else completedMs
    }

    fun reset() {
        activeSinceMs = null
        completedMs = 0L
    }

    private fun safePlus(a: Long, b: Long): Long =
        if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
}

/** ListenBrainz scrobble policy: half a track or four minutes, whichever first. */
internal fun listenBrainzQualifiesAsListen(
    playedMs: Long,
    durationMs: Long?,
): Boolean {
    if (playedMs <= 0L) return false
    val halfTrackMs = durationMs?.takeIf { it > 0L }?.let {
        it / 2L + it % 2L
    } ?: 240_000L
    return playedMs >= minOf(halfTrackMs, 240_000L)
}
