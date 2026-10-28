package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Single lifecycle markers: no per-frame telemetry and no track identity. */
internal enum class PauseResumeFadeEvent(val wireName: String) {
    FADE_IN_STARTED("in_started"),
    FADE_IN_COMPLETED("in_completed"),
    FADE_OUT_STARTED("out_started"),
    FADE_OUT_COMPLETED("out_completed"),
    CANCELED("canceled"),
    ENDED_BEFORE_AUDIBLE("ended_before_audible"),
    FAILED_BEFORE_AUDIBLE("failed_before_audible"),
    HANDOFF_PAUSED("handoff_paused"),
    PLAY_REJECTED("play_rejected"),
}

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
    private val sessionOwner: () -> Player? = { null },
    private val onEvent: (PauseResumeFadeEvent, Int) -> Unit = { _, _ -> },
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
        if (owner === target && job?.isActive == true && fadingIn && target.playWhenReady) return
        if (target.playWhenReady && job?.isActive != true && target.volume >= 0.999f) return

        val from = if ((owner === target && job?.isActive == true) || target.isPlaying) {
            target.volume.coerceIn(0f, 1f)
        } else {
            0f
        }
        interrupt(keep = target)
        owner = target
        fadingIn = true
        target.volume = from
        try {
            target.play()
        } catch (failure: RuntimeException) {
            onEvent(PauseResumeFadeEvent.PLAY_REJECTED, duration)
            // A stale/released player can reject play synchronously. Never strand
            // its last audio route at zero gain while the owner is being replaced.
            reset()
            throw failure
        }
        onEvent(PauseResumeFadeEvent.FADE_IN_STARTED, duration)
        job = scope.launch {
            // Buffering cannot consume the fade: wait until the decoder is audible.
            while (isActive && target.playWhenReady && !target.isPlaying && !overlapActive()) {
                // PlayWhenReady may remain true after decoder errors and natural EOS.
                // No audible input will arrive, so retaining a zero-volume deck
                // could mute an eventual recovery outside this envelope.
                if (target.playerError != null) {
                    target.volume = 1f
                    onEvent(PauseResumeFadeEvent.FAILED_BEFORE_AUDIBLE, duration)
                    return@launch
                }
                if (target.playbackState == Player.STATE_ENDED) {
                    target.volume = 1f
                    onEvent(PauseResumeFadeEvent.ENDED_BEFORE_AUDIBLE, duration)
                    return@launch
                }
                delay(FRAME_MS)
            }
            if (!isActive) return@launch
            if (!target.playWhenReady) {
                target.volume = 1f
                return@launch
            }
            if (overlapActive()) return@launch // the two-deck controller owns gain now
            // Count audible playback time, not wall-clock time. Network buffering
            // halfway through a fade must not consume the remaining ramp during
            // silence and then deliver a full-volume attack when sound resumes.
            var audibleElapsedMs = 0L
            var lastAudibleTickMs: Long? = null
            while (isActive) {
                if (overlapActive()) return@launch
                if (!target.playWhenReady) {
                    target.volume = 1f
                    return@launch
                }
                val nowMs = clockMs()
                if (target.isPlaying) {
                    lastAudibleTickMs?.let { previous ->
                        audibleElapsedMs += (nowMs - previous).coerceAtLeast(0L)
                    }
                    lastAudibleTickMs = nowMs
                } else {
                    lastAudibleTickMs = null
                }
                target.volume = PauseResumeFadeCurve.gain(from, 1f, audibleElapsedMs, duration)
                if (audibleElapsedMs >= duration) break
                delay(FRAME_MS)
            }
            if (isActive && !overlapActive()) {
                target.volume = 1f
                onEvent(PauseResumeFadeEvent.FADE_IN_COMPLETED, duration)
            }
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
        onEvent(PauseResumeFadeEvent.FADE_OUT_STARTED, duration)
        job = scope.launch {
            val start = clockMs()
            while (isActive) {
                if (overlapActive()) {
                    // Handoff can change the MediaSession owner during the fade.
                    // Preserve the PAUSE intent on the new owner as well as the old deck.
                    target.pause()
                    sessionOwner()?.takeIf { it !== target }?.pause()
                    onEvent(PauseResumeFadeEvent.HANDOFF_PAUSED, duration)
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
                onEvent(PauseResumeFadeEvent.FADE_OUT_COMPLETED, duration)
            }
        }
    }

    /**
     * Stops an envelope if a track/route/owner is replaced or a skip/stop occurs.
     * Never overrides the faders during an active Crossfade/Automix overlap.
     */
    fun reset() = interrupt(keep = null)

    /**
     * Used when settings or output routing invalidate the envelope but not the
     * user's PAUSE intention. Seeking/skipping intentionally call [reset] instead.
     */
    fun settlePendingPause() {
        val shouldPause = owner != null && job?.isActive == true && !fadingIn
        val previous = owner
        reset()
        if (shouldPause) {
            previous?.pause()
            sessionOwner()?.takeIf { it !== previous }?.pause()
        }
    }

    private fun interrupt(keep: Player?) {
        if (job?.isActive == true) {
            onEvent(PauseResumeFadeEvent.CANCELED, PauseResumeFadeCurve.normalizeDuration(durationMs()))
        }
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
