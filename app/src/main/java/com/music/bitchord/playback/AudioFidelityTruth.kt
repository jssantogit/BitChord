package com.music.bitchord.playback

/**
 * Truth confined to the PCM samples handed to AudioTrack, not the full
 * Android/output chain. Neither a direct playback request nor observation
 * alone proves mixer/HAL/DAC sample preservation.
 */
internal enum class FidelityTruth {
    ALTERED,
    PCM_EXACT_ROUTE_NOT_PROVEN,
}

internal object AudioFidelityTruth {
    fun classify(pcmExact: Boolean, anyDsp: Boolean): FidelityTruth = when {
        anyDsp || !pcmExact -> FidelityTruth.ALTERED
        else -> FidelityTruth.PCM_EXACT_ROUTE_NOT_PROVEN
    }
}
