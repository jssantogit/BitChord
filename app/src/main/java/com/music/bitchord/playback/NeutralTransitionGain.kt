package com.music.bitchord.playback

/**
 * Conservative overlap gains applied to the two ExoPlayers, not to decoded PCM.
 *
 * A pair of full-scale samples can add constructively in the Android mixer.
 * Keeping the sum of the absolute gains at or below unity prevents our own
 * crossfade from exceeding full scale, without running a compressor or limiter
 * during normal playback. Outside the overlap both endpoints are unity/zero.
 *
 * Equal-power faders still determine the character of the blend; only when
 * their combined gain exceeds unity do we scale the pair together. This is a
 * deliberate headroom-versus-loudness tradeoff during transitions.
 */
internal object NeutralTransitionGain {
    data class Pair(val incoming: Float, val outgoing: Float)

    /**
     * Recover the session player from its current fade gain while releasing
     * the other player. Unlike abruptly setting the survivor to 1, this
     * smoothstep keeps the two-player gain sum bounded at every tick.
     *
     * The returned Pair uses incoming=survivor, outgoing=departing.
     */
    fun recoverSingleTrack(survivor: Float, departing: Float, progress: Float): Pair {
        val start = fromFaders(survivor, departing)
        val t = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val ease = t * t * (3f - 2f * t)
        return fromFaders(
            start.incoming + (1f - start.incoming) * ease,
            start.outgoing * (1f - ease),
        )
    }

    fun fromFaders(incoming: Float, outgoing: Float): Pair {
        val inGain = incoming.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val outGain = outgoing.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val sum = inGain + outGain
        val scale = if (sum > 1f) 1f / sum else 1f
        return Pair(inGain * scale, outGain * scale)
    }
}
