package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A transport-only volume envelope. It never changes sample data, AudioTrack format or
 * playback speed. The current session deck is its sole owner; a real two-deck overlap
 * (Crossfade/Automix or version swap) always takes priority over this envelope.
 *
 * Called on the MediaSession/main dispatcher, as are all Player volume mutations.
 */
internal class PauseResumeVolumeFade(
    private val scope: CoroutineScope,
    private val durationMs: () -> Int,
    private val overlapActive: () -> Boolean,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var job: Job? = null
    private var owner: Player? = null
    private var fadingIn = false

    fun play(target: Player) {
        val duration = PauseResumeFadeCurve.normalizeDuration(durationMs())
        if (duration == 0 || overlapActive()) {
            reset()
            target.play()
            return
        }
        // Repeated play while a fade-in is running must not restart the envelope.
        if (owner === target && job?.isActive == true && fadingIn) return
        if (target.playWhenReady && job?.isActive != true) return

        val from = if (owner === target && job?.isActive == true) {
            target.volume.coerceIn(0f, 1f)
        } else {
            0f
        }
        interrupt(keep = target)
        owner = target
        fadingIn = true
        target.volume = from
        target.play()
        job = scope.launch {
            // Buffering cannot consume the fade: wait until the decoder is audible.
            while (isActive && target.playWhenReady && !target.isPlaying && !overlapActive()) {
                delay(FRAME_MS)
            }
            if (!isActive) return@launch
            if (!target.playWhenReady) {
                target.volume = 1f
                return@launch
            }
            if (overlapActive()) return@launch // the two-deck controller owns gain now
            val start = clockMs()
            while (isActive) {
                if (overlapActive()) return@launch
                if (!target.playWhenReady) {
                    target.volume = 1f
                    return@launch
                }
                val elapsed = (clockMs() - start).coerceAtLeast(0L)
                target.volume = PauseResumeFadeCurve.gain(from, 1f, elapsed, duration)
                if (elapsed >= duration) break
                delay(FRAME_MS)
            }
            if (isActive && !overlapActive()) target.volume = 1f
        }
    }

    fun pause(target: Player) {
        // Multiple PAUSE requests during the same envelope cannot postpone the pause.
        if (owner === target && job?.isActive == true && !fadingIn &&
            durationMs() > 0 && !overlapActive()
        ) return
        val duration = PauseResumeFadeCurve.normalizeDuration(durationMs())
        if (duration == 0 || overlapActive() || !target.isPlaying) {
            reset()
            target.pause()
            return
        }
        val from = target.volume.coerceIn(0f, 1f)
        interrupt(keep = target)
        owner = target
        fadingIn = false
        job = scope.launch {
            val start = clockMs()
            while (isActive) {
                if (overlapActive()) {
                    target.pause()
                    return@launch
                }
                if (!target.playWhenReady) {
                    target.volume = 1f
                    return@launch
                }
                val elapsed = (clockMs() - start).coerceAtLeast(0L)
                target.volume = PauseResumeFadeCurve.gain(from, 0f, elapsed, duration)
                if (elapsed >= duration) break
                delay(FRAME_MS)
            }
            if (isActive) {
                // Only the player's own playhead is paused; resume starts from here.
                target.pause()
                target.volume = 1f
            }
        }
    }

    /**
     * Stops an envelope if a track/route/owner is replaced or a skip/stop occurs.
     * Never overrides the faders during an active Crossfade/Automix overlap.
     */
    fun reset() = interrupt(keep = null)

    private fun interrupt(keep: Player?) {
        job?.cancel()
        job = null
        val previous = owner
        if (previous != null && previous !== keep && !overlapActive()) previous.volume = 1f
        owner = keep
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
