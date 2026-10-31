package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0FlacTimelineRecoveryPolicyTest {
    private val block=4096
    private val total=4_718_700L
    private val frames=((total+block-1)/block).toInt()
    private val duration=total*1000L/44_100L
    private fun check(
        resets:Int=5,regressionMs:Long=22_662L,
        fixed:Int?=block,totalSamples:Long?=total,
        rate:Int?=44_100,actualDuration:Long=duration,
        packets:Int=frames,demuxBytes:Long=11_000_000L,
        sourceBytes:Long=12_000_000L,
    )=A0FlacTimelineRecoveryPolicy.assess(
        resets,regressionMs,fixed,totalSamples,rate,actualDuration,
        packets,demuxBytes,sourceBytes)

    @Test fun recurring22SecondAndroidFlacResetNeedsFullIndependentEvidence() {
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.VERIFIED_FIXED_BLOCK_COUNT,
            check())
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.NOT_NEEDED,
            check(resets=0))
    }
    @Test fun neverApproveShortIncompleteOrVariableFrameAudio() {
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.FRAME_COUNT_MISMATCH,
            check(packets=245))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.NO_INDEPENDENT_STREAMINFO,
            check(fixed=null))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.NO_INDEPENDENT_STREAMINFO,
            check(totalSamples=null))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.INSUFFICIENT_DEMUX_BYTES,
            check(demuxBytes=500_000))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.STREAMINFO_DURATION_MISMATCH,
            check(actualDuration=duration+5000))
    }
    @Test fun disallowUnboundedOrUnrealisticTimestampResets() {
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.EXCESSIVE_RESETS,
            check(resets=65))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.EXCESSIVE_RESETS,
            check(regressionMs=70_000))
    }
}
