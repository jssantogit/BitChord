package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.SoulseekQualityPreset

/**
 * Consumes the dedicated Android Settings choice without touching the legacy
 * DownloadQuality enum. No quality hint authorizes or initiates a transfer.
 */
internal object A0SoulseekQualityProfiles {
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
