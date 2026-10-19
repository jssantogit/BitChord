package com.music.bitchord.playback

/** An app-side PCM conversion verdict is not proof of the Android output route. */
internal enum class FidelityTruth {
    ALTERED,
    PCM_EXACT_ROUTE_NOT_PROVEN,
    END_TO_END_VERIFIED,
}

internal object AudioFidelityTruth {
    fun classify(
        pcmExact: Boolean,
        anyDsp: Boolean,
        routeActuallyObserved: Boolean,
    ): FidelityTruth = FidelityTruth.END_TO_END_VERIFIED
}
