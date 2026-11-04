package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0ObservedAudioBitrateTest {
    @Test fun opusAndVorbisHaveObservedAverageWithoutPeerClaims() {
        assertEquals(160,A0ObservedAudioBitrate.fromDemuxedSamples(3_220_000L,161_000L))
        assertEquals(192,A0ObservedAudioBitrate.fromDemuxedSamples(3_864_000L,161_000L))
    }

    @Test fun roundingDoesNotLoseNominalRateButLowerAverageStaysBelowMinimum() {
        assertEquals(160,A0ObservedAudioBitrate.fromDemuxedSamples(3_214_000L,161_000L))
        assertEquals(159,A0ObservedAudioBitrate.fromDemuxedSamples(3_197_900L,161_000L))
    }

    @Test fun invalidOrImpossiblePayloadEvidenceNeverBecomesQualityProof() {
        assertNull(A0ObservedAudioBitrate.fromDemuxedSamples(0,161_000))
        assertNull(A0ObservedAudioBitrate.fromDemuxedSamples(3_220_000,0))
        assertNull(A0ObservedAudioBitrate.fromDemuxedSamples(40,161_000))
        assertNull(A0ObservedAudioBitrate.fromDemuxedSamples(30_000_000,161_000))
        assertNull(A0ObservedAudioBitrate.fromDemuxedSamples(
            A0ObservedTransferLimits.MAX_AUDIO_BYTES+1,161_000))
    }
}
