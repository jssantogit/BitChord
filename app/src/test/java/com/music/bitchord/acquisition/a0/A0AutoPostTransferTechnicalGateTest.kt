package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQualityProfile
import org.junit.Assert.*
import org.junit.Test

class A0AutoPostTransferTechnicalGateTest {
    private fun profile(value:DownloadQualityProfile)=
        A0SoulseekQualityProfiles.snapshot(value)
    private fun decoded(codec:A0CanonicalAcquisitionIntent.Codec,
        ext:String,seconds:Int=161,rate:Int?=null,depth:Int?=null,
        bitrate:Int?=null,completed:Boolean=true)=
        A0AutoPostTransferTechnicalGate.Decoded(
            codec,ext,seconds*1000L,bitrate,rate,depth,completed)
    private fun verify(
        profile:DownloadQualityProfile,
        ext:String,
        observed:A0AutoPostTransferTechnicalGate.Decoded?,
        received:Long=6_440_000L,
        actual:Long=received,
        target:Int?=161,
    )=A0AutoPostTransferTechnicalGate.assess(
        profile(profile),ext,target,received,actual,observed)

    @Test fun locallyParsedLosslessAndHiResRemainOnlyTechnicalEvidence() {
        val flac=decoded(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",
            rate=48_000,depth=16)
        val accepted=verify(DownloadQualityProfile.LOSSLESS,"flac",flac)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE,
            accepted.status)
        assertFalse(accepted.canPublishAutomatically)
        assertFalse(accepted.provesMusicIdentity)
        val hiRes=decoded(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",
            rate=96_000,depth=24)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE,
            verify(DownloadQualityProfile.HI_RES,"flac",hiRes).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.HI_RES,"flac",flac).status)
    }

    @Test fun claimedLosslessWithoutLocallyParsedPcmCharacteristicsStaysReview() {
        val incomplete=decoded(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",
            rate=null,depth=null)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REVIEW,
            verify(DownloadQualityProfile.LOSSLESS,"flac",incomplete).status)
        val noDepth=decoded(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",
            rate=96000,depth=null)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REVIEW,
            verify(DownloadQualityProfile.HI_RES,"flac",noDepth).status)
    }

    @Test fun realHiResLosslessBitrateAboveLossyLimitsMustNotBeRejected() {
        // 24-bit 96kHz stereo uncompressed PCM is 4608 kbps.
        val wav=decoded(A0CanonicalAcquisitionIntent.Codec.PCM_WAV,"wav",
            rate=96_000,depth=24,bitrate=4608)
        val flac=decoded(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",
            rate=96_000,depth=24,bitrate=3200)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE,
            verify(DownloadQualityProfile.HI_RES,"wav",wav).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE,
            verify(DownloadQualityProfile.HI_RES,"flac",flac).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.HI_RES,"wav",
                wav.copy(bitrateKbps=150_000)).status)
    }

    @Test fun actualMp3QualityCannotBeUpgradedByAFalseFlacFilename() {
        val mp3=decoded(A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",bitrate=320)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.TECHNICALLY_PLAUSIBLE,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",mp3).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.LOSSLESS,"mp3",mp3).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.HIGH_QUALITY,"flac",mp3).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",
                decoded(A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",bitrate=512)).status)
    }

    @Test fun damagedOrUnparsedAudioNeverGainsTechnicalReadiness() {
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REVIEW,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",null).status)
        val incomplete=decoded(A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",
            bitrate=320,completed=false)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REVIEW,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",incomplete).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",incomplete,
                received=6400,actual=6401).status)
    }

    @Test fun divergentDecodedDurationAndUncertainTargetRequireAbstention() {
        val mp3=decoded(A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",
            seconds=181,bitrate=320)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",mp3).status)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REVIEW,
            verify(DownloadQualityProfile.HIGH_QUALITY,"mp3",mp3,target=null).status)
    }

    @Test fun impossibleDecoderFieldsAndUnsupportedCodecFailClosed() {
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.LOSSLESS,"flac",
                decoded(A0CanonicalAcquisitionIntent.Codec.FLAC,"flac",
                    rate=800_000,depth=24)).status)
        val unsupported=A0AutoPostTransferTechnicalGate.Decoded(
            null,"m4a",161_000L,null,null,null,true)
        assertEquals(A0AutoPostTransferTechnicalGate.Status.REJECT,
            verify(DownloadQualityProfile.LOSSLESS,"m4a",unsupported).status)
    }
}
