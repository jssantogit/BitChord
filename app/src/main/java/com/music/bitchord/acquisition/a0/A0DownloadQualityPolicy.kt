package com.music.bitchord.acquisition.a0

import java.util.Locale

/**
 * Audio-quality CLAIM preflight. This does not probe file bytes, transcode,
 * confirm a master, or authorize the P/F/manual downloader. Runtime-supported
 * codecs are supplied explicitly, never inferred from a preset name.
 *
 * Preset UI and preference migration are future integration tasks; existing
 * DownloadQuality behaviour is unchanged by this isolated policy.
 */
internal object A0DownloadQualityPolicy {
    enum class Status {
        NOT_CONSTRAINED, COMPATIBLE_CLAIM_UNVERIFIED,
        INCONCLUSIVE_MANUAL, INCOMPATIBLE, UNAVAILABLE, INVALID_PROFILE,
    }
    enum class Reason {
        LEGACY_DEFAULT, CLAIM_MATCHES_REQUEST, UNKNOWN_CODEC,
        CONTAINER_CODEC_CONFLICT, UNSUPPORTED_CODEC, PRESET_NOT_SUPPORTED,
        REQUESTED_CODEC_CONFLICT, INSUFFICIENT_TECHNICAL_CLAIMS,
        ADVERTISED_QUALITY_BELOW_REQUIREMENT, INVALID_CODEC_CONSTRAINT,
    }

    class Offer(
        val codecClaim: A0CanonicalAcquisitionIntent.Codec?,
        val extension: String,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null,
        val bitDepth: Int? = null,
    ) {
        override fun toString() = "DownloadQualityOffer(redacted)"
    }

    class Assessment(val status: Status, val reason: Reason) {
        val provesAudioMaster: Boolean get() = false
        val authorizesTransfer: Boolean get() = false
        override fun toString() = "DownloadQualityAssessment(status=$status)"
    }

    private fun uncertain(reason: Reason) = Assessment(Status.INCONCLUSIVE_MANUAL, reason)
    private fun match() = Assessment(
        Status.COMPATIBLE_CLAIM_UNVERIFIED, Reason.CLAIM_MATCHES_REQUEST
    )
    private fun mismatch(strict: Boolean) = Assessment(
        if (strict) Status.INCOMPATIBLE else Status.INCONCLUSIVE_MANUAL,
        Reason.ADVERTISED_QUALITY_BELOW_REQUIREMENT
    )

    fun evaluate(
        profile: A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        offer: Offer,
        supportedCodecs: Set<A0CanonicalAcquisitionIntent.Codec>,
    ): Assessment {
        val preset = profile.preset
        if (preset == A0CanonicalAcquisitionIntent.QualityPreset.LEGACY_UNSPECIFIED) {
            return Assessment(Status.NOT_CONSTRAINED, Reason.LEGACY_DEFAULT)
        }
        // The current Android saving path supports MP3, FLAC, WAV. This is
        // not an HE-AAC/Opus or ALAC pipeline. Do not invent a transcoder.
        if (preset == A0CanonicalAcquisitionIntent.QualityPreset.HIGH_EFFICIENCY) {
            return Assessment(Status.UNAVAILABLE, Reason.PRESET_NOT_SUPPORTED)
        }
        val codec = offer.codecClaim
        val lossless = codec == A0CanonicalAcquisitionIntent.Codec.FLAC ||
            codec == A0CanonicalAcquisitionIntent.Codec.PCM_WAV

        // Bit depth and sample rate only describe meaningful PCM/lossless
        // assertions here; even Custom must not apply them to MP3.
        if (preset == A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM &&
            (profile.minBitDepth != null || profile.minSampleRateHz != null) &&
            profile.allowedCodecs.isNotEmpty() &&
            profile.allowedCodecs.none {
                it == A0CanonicalAcquisitionIntent.Codec.FLAC ||
                    it == A0CanonicalAcquisitionIntent.Codec.PCM_WAV
            }) {
            return Assessment(Status.INVALID_PROFILE, Reason.INVALID_CODEC_CONSTRAINT)
        }
        if (codec == null) return uncertain(Reason.UNKNOWN_CODEC)
        val ext = offer.extension.trim().trimStart('.').lowercase(Locale.ROOT)
        val expectedExt = when (codec) {
            A0CanonicalAcquisitionIntent.Codec.MP3 -> "mp3"
            A0CanonicalAcquisitionIntent.Codec.FLAC -> "flac"
            A0CanonicalAcquisitionIntent.Codec.PCM_WAV -> "wav"
        }
        if (ext != expectedExt) return uncertain(Reason.CONTAINER_CODEC_CONFLICT)
        if (codec !in supportedCodecs) {
            return Assessment(Status.UNAVAILABLE, Reason.UNSUPPORTED_CODEC)
        }

        when (preset) {
            A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS -> {
                return if (lossless) match() else mismatch(profile.strict)
            }
            A0CanonicalAcquisitionIntent.QualityPreset.HI_RES -> {
                if (!lossless) return mismatch(profile.strict)
                val depth = offer.bitDepth
                val rate = offer.sampleRateHz
                if (depth == null || rate == null)
                    return uncertain(Reason.INSUFFICIENT_TECHNICAL_CLAIMS)
                return if (depth >= 24 && rate > 48_000) match()
                    else mismatch(profile.strict)
            }
            A0CanonicalAcquisitionIntent.QualityPreset.HIGH_QUALITY -> {
                if (lossless) return match()
                val rate = offer.bitrateKbps
                    ?: return uncertain(Reason.INSUFFICIENT_TECHNICAL_CLAIMS)
                return if (rate in 256..320) match() else mismatch(profile.strict)
            }
            A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM -> {
                if (profile.allowedCodecs.isNotEmpty() &&
                    codec !in profile.allowedCodecs) {
                    return Assessment(
                        if (profile.strict) Status.INCOMPATIBLE else Status.INCONCLUSIVE_MANUAL,
                        Reason.REQUESTED_CODEC_CONFLICT
                    )
                }
                if (!lossless && (profile.minBitDepth != null ||
                    profile.minSampleRateHz != null)) {
                    return Assessment(Status.INVALID_PROFILE, Reason.INVALID_CODEC_CONSTRAINT)
                }
                if (profile.minBitrateKbps != null) {
                    val announced = offer.bitrateKbps
                        ?: return uncertain(Reason.INSUFFICIENT_TECHNICAL_CLAIMS)
                    if (announced < profile.minBitrateKbps) return mismatch(profile.strict)
                }
                if (profile.minSampleRateHz != null) {
                    val announced = offer.sampleRateHz
                        ?: return uncertain(Reason.INSUFFICIENT_TECHNICAL_CLAIMS)
                    if (announced < profile.minSampleRateHz) return mismatch(profile.strict)
                }
                if (profile.minBitDepth != null) {
                    val announced = offer.bitDepth
                        ?: return uncertain(Reason.INSUFFICIENT_TECHNICAL_CLAIMS)
                    if (announced < profile.minBitDepth) return mismatch(profile.strict)
                }
                return match()
            }
            // Handled above, kept explicit to avoid surprising fallback.
            A0CanonicalAcquisitionIntent.QualityPreset.LEGACY_UNSPECIFIED,
            A0CanonicalAcquisitionIntent.QualityPreset.HIGH_EFFICIENCY ->
                return Assessment(Status.NOT_CONSTRAINED, Reason.LEGACY_DEFAULT)
        }
    }
}
