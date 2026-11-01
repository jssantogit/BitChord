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
        status:A0FlacWholeFileEvidence.Status?=
            A0FlacWholeFileEvidence.Status.VERIFIED,
        scannedSamples:Long=total,scannedFrames:Int=frames,
        scannedBytes:Long=12_000_000L,sourceBytes:Long=12_000_000L,
    )=A0FlacTimelineRecoveryPolicy.assess(
        resets,regressionMs,fixed,totalSamples,rate,actualDuration,
        status,scannedSamples,scannedFrames,scannedBytes,sourceBytes)

    @Test fun verifiedIndependentLocalFileCanReconcileBrokenAndroidPts() {
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.VERIFIED_LOCAL_FLAC_SEQUENCE,
            check())
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.NOT_NEEDED,
            check(resets=0))
    }
    @Test fun neverTrustsAndroidPacketsWithoutIndependentWholeFileScan() {
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.INDEPENDENT_FILE_UNVERIFIED,
            check(status=null))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.INDEPENDENT_FILE_UNVERIFIED,
            check(status=A0FlacWholeFileEvidence.Status.FRAME_CRC_MISMATCH))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.INDEPENDENT_FILE_UNVERIFIED,
            check(status=A0FlacWholeFileEvidence.Status.TIME_LIMIT))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.FRAME_SAMPLE_COVERAGE_MISMATCH,
            check(scannedSamples=total-4096))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.FRAME_SAMPLE_COVERAGE_MISMATCH,
            check(scannedFrames=0))
    }
    @Test fun incompleteOrContradictoryLocalAudioNeverApproved() {
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.NO_INDEPENDENT_STREAMINFO,
            check(fixed=null))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.NO_INDEPENDENT_STREAMINFO,
            check(totalSamples=null))
        assertEquals(A0FlacTimelineRecoveryPolicy.Verdict.INCOMPLETE_FILE_EVIDENCE,
            check(scannedBytes=500_000))
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
