package com.music.bitchord.playback

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality

/**
 * Snapshot of existing user quality ceilings. Playback intentionally owns no
 * competing preferences or persistence layer.
 */
internal data class CanonicalRemoteQualityPreferences(
    val wifi: AudioQuality,
    val mobile: AudioQuality,
) {
    companion object {
        fun current(): CanonicalRemoteQualityPreferences =
            CanonicalRemoteQualityPreferences(
                wifi = AppSettings.audioQualityWifi.value,
                mobile = AppSettings.audioQualityCellular.value,
            )
    }
}

/**
 * Physical transport classification, NOT inferred from metering. A metered
 * Wi-Fi hotspot is still Wi-Fi, and an unmetered mobile network is mobile.
 */
internal enum class CanonicalRemoteNetwork { WIFI, MOBILE, OTHER, UNKNOWN }

internal enum class CanonicalRemoteSource { LOCAL, OPENSUBSONIC, OTHER_REMOTE }

/** Never promote an extension announcement to per-stream proof. */
internal enum class CanonicalRemoteTranscodeCapability {
    UNKNOWN, UNSUPPORTED, ADVERTISED_ONLY, VERIFIED_MP3,
}

internal enum class CanonicalRemoteQualityDecision {
    ORIGINAL_DIRECT,
    CAPABILITY_VERIFIED_INTENT,
    CAPABILITY_UNKNOWN,
    CAPABILITY_UNSUPPORTED,
    UNKNOWN_NETWORK,
    NOT_OPENSUBSONIC,
}

/**
 * This is an intent preview only: it cannot state that a stream opened, that
 * a provider negotiated quality, or that decoded audio became audible.
 */
internal data class CanonicalRemoteQualityPreview(
    val network: CanonicalRemoteNetwork,
    val isMetered: Boolean?,
    val chosenPreference: AudioQuality?,
    val decision: CanonicalRemoteQualityDecision,
    val requestFormat: String?,
    val maxBitrateKbps: Int?,
    val applied: Boolean = false,
    val negotiatedFormat: String? = null,
    val observedFormat: String? = null,
)

internal object CanonicalRemoteQualityPlanner {
    /**
     * Pure and side-effect-free. The only non-original request offered in
     * preview is bounded MP3/HTTP for a *verified* capability. Actual support
     * is determined per asset/account at Catalog's DataSource.open seam in
     * B13, not from a radio/metering assumption or a saved preference.
     *
     * A network change affects only subsequent previews. Never reopen the
     * playing stream, change Media3 or issue network calls here.
     */
    fun preview(
        preferences: CanonicalRemoteQualityPreferences,
        network: CanonicalRemoteNetwork,
        metered: Boolean?,
        source: CanonicalRemoteSource,
        capability: CanonicalRemoteTranscodeCapability,
    ): CanonicalRemoteQualityPreview {
        val selected = when (network) {
            CanonicalRemoteNetwork.WIFI -> preferences.wifi
            CanonicalRemoteNetwork.MOBILE -> preferences.mobile
            CanonicalRemoteNetwork.UNKNOWN, CanonicalRemoteNetwork.OTHER -> null
        }
        fun original(decision: CanonicalRemoteQualityDecision): CanonicalRemoteQualityPreview =
            CanonicalRemoteQualityPreview(
                network = network,
                isMetered = metered,
                chosenPreference = selected,
                decision = decision,
                // Local/non-OpenSubsonic sources have no OpenSubsonic format.
                requestFormat = if (source == CanonicalRemoteSource.OPENSUBSONIC) "raw" else null,
                maxBitrateKbps = null,
            )

        if (source != CanonicalRemoteSource.OPENSUBSONIC) {
            return original(CanonicalRemoteQualityDecision.NOT_OPENSUBSONIC)
        }
        if (selected == null) {
            return original(CanonicalRemoteQualityDecision.UNKNOWN_NETWORK)
        }
        if (selected == AudioQuality.LOSSLESS) {
            return original(CanonicalRemoteQualityDecision.ORIGINAL_DIRECT)
        }
        when (capability) {
            CanonicalRemoteTranscodeCapability.UNSUPPORTED ->
                return original(CanonicalRemoteQualityDecision.CAPABILITY_UNSUPPORTED)
            CanonicalRemoteTranscodeCapability.UNKNOWN,
            CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY ->
                return original(CanonicalRemoteQualityDecision.CAPABILITY_UNKNOWN)
            CanonicalRemoteTranscodeCapability.VERIFIED_MP3 -> Unit
        }
        val ceiling = when (selected) {
            AudioQuality.HIGH -> 320
            AudioQuality.MEDIUM -> 160
            AudioQuality.LOW -> AudioQuality.LOW.maxKbps
            AudioQuality.LOSSLESS -> error("Original handling must return before MP3 planning")
        }
        return CanonicalRemoteQualityPreview(
            network = network,
            isMetered = metered,
            chosenPreference = selected,
            decision = CanonicalRemoteQualityDecision.CAPABILITY_VERIFIED_INTENT,
            requestFormat = "mp3",
            maxBitrateKbps = ceiling,
        )
    }
}
