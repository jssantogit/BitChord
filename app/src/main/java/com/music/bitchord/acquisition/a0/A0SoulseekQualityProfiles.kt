package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.SoulseekQualityPreset
import com.music.bitchord.data.settings.DownloadQualityProfile
import com.music.bitchord.data.settings.DownloadCustomOptions

/**
 * Consumes the dedicated Android Settings choice without touching the legacy
 * DownloadQuality enum. No quality hint authorizes or initiates a transfer.
 */
internal object A0SoulseekQualityProfiles {
    /**
     * HIGH_QUALITY offers distinct efficient lossy codecs at codec-specific
     * thresholds. ALAC/M4A remains unsupported until the container and
     * audio codec can be verified by a local extractor and A2 publisher.
     */
    fun snapshot(profile:DownloadQualityProfile,custom:DownloadCustomOptions=DownloadCustomOptions()):
        A0CanonicalAcquisitionIntent.QualityProfileSnapshot {
        return when(profile) {
            DownloadQualityProfile.HI_RES ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.HI_RES,strict=true)
            DownloadQualityProfile.LOSSLESS ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS,strict=true)
            DownloadQualityProfile.HIGH_QUALITY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.HIGH_QUALITY,strict=true)
            DownloadQualityProfile.CUSTOM -> {
                val codec=when(custom.format) {
                    "mp3" -> A0CanonicalAcquisitionIntent.Codec.MP3
                    "flac" -> A0CanonicalAcquisitionIntent.Codec.FLAC
                    "wav" -> A0CanonicalAcquisitionIntent.Codec.PCM_WAV
                    "aac" -> A0CanonicalAcquisitionIntent.Codec.AAC_ADTS
                    "opus" -> A0CanonicalAcquisitionIntent.Codec.OPUS
                    "ogg" -> A0CanonicalAcquisitionIntent.Codec.VORBIS
                    else -> null
                }
                // A requested unsupported custom codec must not expand into
                // every supported format. Empty codecs in CUSTOM means any,
                // so use a deliberately unreachable typed bound instead.
                if(codec==null) A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.HIGH_EFFICIENCY,
                    strict=true)
                else A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM,
                    allowedCodecs=setOf(codec),
                    minBitrateKbps=custom.bitrateKbps?.takeIf {
                        codec in setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
                            A0CanonicalAcquisitionIntent.Codec.AAC_ADTS,
                            A0CanonicalAcquisitionIntent.Codec.OPUS,
                            A0CanonicalAcquisitionIntent.Codec.VORBIS) },
                    minSampleRateHz=custom.sampleRateHz?.takeIf {
                        codec !in setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
                            A0CanonicalAcquisitionIntent.Codec.AAC_ADTS,
                            A0CanonicalAcquisitionIntent.Codec.OPUS,
                            A0CanonicalAcquisitionIntent.Codec.VORBIS) },
                    minBitDepth=custom.bitDepth?.takeIf {
                        codec in setOf(A0CanonicalAcquisitionIntent.Codec.FLAC,
                            A0CanonicalAcquisitionIntent.Codec.PCM_WAV) },
                    strict=true,
                )
            }
        }
    }

    fun snapshot(value: SoulseekQualityPreset):
        A0CanonicalAcquisitionIntent.QualityProfileSnapshot {
        return when(value) {
            SoulseekQualityPreset.ANY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot()
            SoulseekQualityPreset.HI_RES ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.HI_RES, strict=true)
            SoulseekQualityPreset.LOSSLESS ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS, strict=true)
            SoulseekQualityPreset.HIGH_QUALITY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.HIGH_QUALITY, strict=true)
            SoulseekQualityPreset.HIGH_EFFICIENCY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.HIGH_EFFICIENCY, strict=true)
            SoulseekQualityPreset.MP3_256 ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM, allowedCodecs=setOf(A0CanonicalAcquisitionIntent.Codec.MP3),
                    minBitrateKbps=256,strict=true)
            SoulseekQualityPreset.FLAC_24_96 ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM, allowedCodecs=setOf(A0CanonicalAcquisitionIntent.Codec.FLAC),
                    minSampleRateHz=96000,minBitDepth=24,strict=true)
        }
    }
}
