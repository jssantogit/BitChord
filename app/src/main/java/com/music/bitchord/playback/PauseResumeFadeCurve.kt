package com.music.bitchord.playback

/** Smooth volume automation at transport boundaries; never touches decoded PCM. */
internal object PauseResumeFadeCurve {
    const val DEFAULT_DURATION_MS = 200
    const val MAX_DURATION_MS = 600

    fun normalizeDuration(requestedMs: Int): Int = requestedMs.coerceIn(0, MAX_DURATION_MS)

    fun gain(from: Float, to: Float, elapsedMs: Long, durationMs: Int): Float {
        val start = from.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val end = to.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: start
        val progress = if (durationMs <= 0) 1f else {
            (elapsedMs.coerceAtLeast(0L).toFloat() / durationMs).coerceIn(0f, 1f)
        }
        val smooth = progress * progress * (3f - 2f * progress)
        return (start + (end - start) * smooth).coerceIn(0f, 1f)
    }
}
