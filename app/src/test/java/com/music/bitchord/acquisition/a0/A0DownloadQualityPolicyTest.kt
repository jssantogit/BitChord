package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** #177: format and bitrate are peer claims, not verified audio or transcoders. */
class A0DownloadQualityPolicyTest {
    private val supported=setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
        A0CanonicalAcquisitionIntent.Codec.FLAC,
        A0CanonicalAcquisitionIntent.Codec.PCM_WAV)

    private fun profile(
        preset:A0CanonicalAcquisitionIntent.QualityPreset,
        strict:Boolean=true,
    )=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(preset=preset, strict=strict)

    @Test fun highResolutionNeedsLossless24BitAndAbove48kHzAsClaims() {
        val policy=profile(A0CanonicalAcquisitionIntent.QualityPreset.HI_RES)
        val good=A0DownloadQualityPolicy.evaluate(policy,
            A0DownloadQualityPolicy.Offer(
                codecClaim=A0CanonicalAcquisitionIntent.Codec.FLAC,
                extension="flac", sampleRateHz=96_000, bitDepth=24),
            supported)
        assertEquals(A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED, good.status)
        assertFalse(good.provesAudioMaster)
        assertFalse(good.authorizesTransfer)
        val cd=A0DownloadQualityPolicy.evaluate(policy,
            A0DownloadQualityPolicy.Offer(
                codecClaim=A0CanonicalAcquisitionIntent.Codec.FLAC,
                extension="flac", sampleRateHz=44_100, bitDepth=16),
            supported)
        assertEquals(A0DownloadQualityPolicy.Status.INCOMPATIBLE, cd.status)
    }

    @Test fun advertisedLosslessDoesNotAllowStrictMp3Fallback() {
        val p=profile(A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS)
        val r=A0DownloadQualityPolicy.evaluate(p,
            A0DownloadQualityPolicy.Offer(
                codecClaim=A0CanonicalAcquisitionIntent.Codec.MP3,
                extension="mp3", bitrateKbps=320),supported)
        assertEquals(A0DownloadQualityPolicy.Status.INCOMPATIBLE, r.status)
    }

    @Test fun advertisedCodecWithoutSupportedPlaybackIsNotPromoted() {
        val p=profile(A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS)
        val offer=A0DownloadQualityPolicy.Offer(
            codecClaim=A0CanonicalAcquisitionIntent.Codec.FLAC,
            extension="flac", sampleRateHz=96_000, bitDepth=24)
        assertEquals(A0DownloadQualityPolicy.Status.UNAVAILABLE,
            A0DownloadQualityPolicy.evaluate(p,offer,setOf(
                A0CanonicalAcquisitionIntent.Codec.MP3)).status)
    }

    @Test fun m4aContainerCannotPretendToBeAlacOrAacByExtension() {
        val p=profile(A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS)
        assertEquals(A0DownloadQualityPolicy.Status.INCONCLUSIVE_MANUAL,
            A0DownloadQualityPolicy.evaluate(p,
                A0DownloadQualityPolicy.Offer(codecClaim=null, extension="m4a"),supported).status)
    }

    @Test fun declaredCodecAndContainerConflictAreNotCompatible() {
        val p=profile(A0CanonicalAcquisitionIntent.QualityPreset.HI_RES)
        assertEquals(A0DownloadQualityPolicy.Status.INCONCLUSIVE_MANUAL,
            A0DownloadQualityPolicy.evaluate(p,
                A0DownloadQualityPolicy.Offer(
                    codecClaim=A0CanonicalAcquisitionIntent.Codec.FLAC,
                    extension="mp3", sampleRateHz=96_000, bitDepth=24),supported).status)
    }

    @Test fun lossyBitrateIsNotPcmDepth() {
        val p=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
            preset=A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM,
            allowedCodecs=setOf(A0CanonicalAcquisitionIntent.Codec.MP3),
            minBitDepth=24, strict=true)
        assertEquals(A0DownloadQualityPolicy.Status.INVALID_PROFILE,
            A0DownloadQualityPolicy.evaluate(p,
                A0DownloadQualityPolicy.Offer(
                    codecClaim=A0CanonicalAcquisitionIntent.Codec.MP3,
                    extension="mp3", bitrateKbps=320),supported).status)
    }

    @Test fun unsupportedHighEfficiencyPresetCannotPromiseAacOrOpus() {
        val p=profile(A0CanonicalAcquisitionIntent.QualityPreset.HIGH_EFFICIENCY)
        assertEquals(A0DownloadQualityPolicy.Status.UNAVAILABLE,
            A0DownloadQualityPolicy.evaluate(p,
                A0DownloadQualityPolicy.Offer(
                    codecClaim=A0CanonicalAcquisitionIntent.Codec.MP3,
                    extension="mp3", bitrateKbps=192),supported).status)
    }

    @Test fun customStrictCodecAndMinBitrateAreHonoured() {
        val p=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
            preset=A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM,
            allowedCodecs=setOf(A0CanonicalAcquisitionIntent.Codec.MP3),
            minBitrateKbps=256, strict=true)
        val bad=A0DownloadQualityPolicy.evaluate(p,
            A0DownloadQualityPolicy.Offer(
                codecClaim=A0CanonicalAcquisitionIntent.Codec.MP3,
                extension="mp3", bitrateKbps=192),supported)
        assertEquals(A0DownloadQualityPolicy.Status.INCOMPATIBLE,bad.status)
        val good=A0DownloadQualityPolicy.evaluate(p,
            A0DownloadQualityPolicy.Offer(
                codecClaim=A0CanonicalAcquisitionIntent.Codec.MP3,
                extension="mp3", bitrateKbps=320),supported)
        assertEquals(A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,good.status)
    }

    @Test fun legacyUnspecifiedHasNoNewDownloadRestriction() {
        val p=A0CanonicalAcquisitionIntent.QualityProfileSnapshot()
        val r=A0DownloadQualityPolicy.evaluate(p,
            A0DownloadQualityPolicy.Offer(
                codecClaim=A0CanonicalAcquisitionIntent.Codec.MP3,
                extension="mp3", bitrateKbps=128),supported)
        assertEquals(A0DownloadQualityPolicy.Status.NOT_CONSTRAINED,r.status)
    }
}
