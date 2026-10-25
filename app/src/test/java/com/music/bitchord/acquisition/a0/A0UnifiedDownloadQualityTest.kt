package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.DownloadQualityProfile
import com.music.bitchord.data.settings.DownloadCustomOptions
import org.junit.Assert.*
import org.junit.Test

class A0UnifiedDownloadQualityTest {
    @Test fun migratedDownloadsKeepOneCanonicalPreference() {
        assertEquals(DownloadQualityProfile.LOSSLESS,
            DownloadQualityProfile.fromLegacy(DownloadQuality.LOSSLESS))
        assertEquals(DownloadQualityProfile.HIGH_QUALITY,
            DownloadQualityProfile.fromLegacy(DownloadQuality.HIGH))
    }

    @Test fun highQualityRejectsFlacAndAcceptsOnlyAdvertised320KbpsMp3() {
        val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
        val supported=setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
            A0CanonicalAcquisitionIntent.Codec.FLAC,A0CanonicalAcquisitionIntent.Codec.PCM_WAV)
        fun assess(codec:A0CanonicalAcquisitionIntent.Codec,ext:String,kbps:Int?) =
            A0DownloadQualityPolicy.evaluate(profile,
                A0DownloadQualityPolicy.Offer(codec,ext,kbps),supported).status
        assertEquals(A0DownloadQualityPolicy.Status.INCOMPATIBLE,
            assess(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",null))
        assertEquals(A0DownloadQualityPolicy.Status.INCOMPATIBLE,
            assess(A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",256))
        assertEquals(A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,
            assess(A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",320))
        val high=A0DownloadQualityPolicy.evaluate(profile,
            A0DownloadQualityPolicy.Offer(A0CanonicalAcquisitionIntent.Codec.AAC_ADTS,"aac",256),
            supported + A0CanonicalAcquisitionIntent.Codec.AAC_ADTS)
        assertEquals(A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,high.status)
    }

    @Test fun customCodecAndBitrateAreRealTypedConstraints() {
        val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.CUSTOM,
            DownloadCustomOptions(format="mp3",bitrateKbps=320,
                sampleRateHz=null,bitDepth=null))
        assertEquals(A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM,profile.preset)
        assertEquals(setOf(A0CanonicalAcquisitionIntent.Codec.MP3),profile.allowedCodecs)
        assertEquals(320,profile.minBitrateKbps)
    }

    @Test fun losslessProfileDoesNotClassifyAacAsLossless() {
        val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.LOSSLESS)
        val result=A0DownloadQualityPolicy.evaluate(profile,
            A0DownloadQualityPolicy.Offer(null,"m4a",256),
            setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
                A0CanonicalAcquisitionIntent.Codec.FLAC,
                A0CanonicalAcquisitionIntent.Codec.PCM_WAV))
        assertNotEquals(A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED,result.status)
    }
}