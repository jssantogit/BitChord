package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.SoulseekQualityPreset

/**
 * Consumes the dedicated Android Settings choice without touching the legacy
 * DownloadQuality enum. No quality hint authorizes or initiates a transfer.
 */
internal object A0SoulseekQualityProfiles {
    fun snapshot(value: SoulseekQualityPreset):
        A0CanonicalAcquisitionIntent.QualityProfileSnapshot {
        val P=A0CanonicalAcquisitionIntent.QualityPreset
        val C=A0CanonicalAcquisitionIntent.Codec
        return when(value) {
            SoulseekQualityPreset.ANY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot()
            SoulseekQualityPreset.HI_RES ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=P.HI_RES, strict=true)
            SoulseekQualityPreset.LOSSLESS ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=P.LOSSLESS, strict=true)
            SoulseekQualityPreset.HIGH_QUALITY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=P.HIGH_QUALITY, strict=true)
            SoulseekQualityPreset.HIGH_EFFICIENCY ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=P.HIGH_EFFICIENCY, strict=true)
            SoulseekQualityPreset.MP3_256 ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=P.CUSTOM, allowedCodecs=setOf(C.MP3),
                    minBitrateKbps=256,strict=true)
            SoulseekQualityPreset.FLAC_24_96 ->
                A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                    preset=P.CUSTOM, allowedCodecs=setOf(C.FLAC),
                    minSampleRateHz=96000,minBitDepth=24,strict=true)
        }
    }
}
