package com.music.bitchord.playback

/** How a neutral overlap ends when it finishes or is cancelled mid-song. */
internal enum class FadeExit { CONTINUE, BAIL, FINISH }

/**
 * Cancelling Crossfade/Automix before the overlap reaches its natural end
 * resumes the current session deck using the safe bail ramp; it must not
 * instantaneously hand off to a half-audible incoming track.
 */
internal object NeutralFadeExitPolicy {
    fun decide(progress: Float, tailDone: Boolean, settingSwitchedOff: Boolean): FadeExit = when {
        settingSwitchedOff && progress < 1f -> FadeExit.BAIL
        settingSwitchedOff -> FadeExit.FINISH
        progress >= 1f && tailDone -> FadeExit.FINISH
        else -> FadeExit.CONTINUE
    }
}
