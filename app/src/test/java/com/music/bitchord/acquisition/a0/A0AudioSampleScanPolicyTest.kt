package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0AudioSampleScanPolicyTest {
    @Test fun boundedLeadingCodecPacketsAreNotMistakenForAudioFrames() {
        assertTrue(A0AudioSampleScanPolicy.skipLeadingEmpty(0,0,true,true))
        assertTrue(A0AudioSampleScanPolicy.skipLeadingEmpty(0,1,true,true))
        assertFalse(A0AudioSampleScanPolicy.skipLeadingEmpty(0,2,true,true))
        assertFalse(A0AudioSampleScanPolicy.skipLeadingEmpty(0,0,true,false))
        assertFalse(A0AudioSampleScanPolicy.skipLeadingEmpty(0,0,false,true))
        assertFalse(A0AudioSampleScanPolicy.skipLeadingEmpty(1,0,true,true))
    }

    @Test fun trailingEmptyAfterCompleteSamplesMayRepresentExtractorEos() {
        assertTrue(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            400,106_900_000L,107_000L,true))
    }

    @Test fun zeroLengthInteriorSampleNeverPretendsToBeEos() {
        assertFalse(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            400,106_900_000L,107_000L,false))
    }

    @Test fun emptyFirstOrOnlySampleDoesNotPass() {
        assertFalse(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            0,0,107_000L,true))
        assertFalse(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            1,106_900_000L,107_000L,true))
    }

    @Test fun earlyEosCannotHideIncompleteAudio() {
        assertFalse(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            400,50_000_000L,107_000L,true))
        assertFalse(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            400,-1,107_000L,true))
        assertFalse(A0AudioSampleScanPolicy.acceptTerminalEmpty(
            400,106_900_000L,0,true))
    }
}
