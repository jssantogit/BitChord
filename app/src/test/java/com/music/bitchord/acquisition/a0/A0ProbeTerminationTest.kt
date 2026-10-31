package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0ProbeTerminationTest {
    private fun outcome(
        eos:Boolean=true,frames:Int=500,last:Long=106_990_000L,
        duration:Long=107_000L,cap:Boolean=false,
        expired:Boolean=false,cancel:Boolean=false,
    )=A0ProbeTermination.classify(eos,frames,last,duration,cap,expired,cancel)

    @Test fun fullAudioMustCoverExpectedDurationAndEndNaturally() {
        assertEquals(A0ProbeTermination.Reason.COMPLETE,outcome())
        assertEquals(A0ProbeTermination.Reason.DURATION_NOT_COVERED,
            outcome(last=30_000_000L))
        assertEquals(A0ProbeTermination.Reason.TOO_FEW_SAMPLES,outcome(frames=1))
        assertEquals(A0ProbeTermination.Reason.NO_SAMPLE_TIMESTAMP,outcome(last=-1))
    }

    @Test fun expiredCancelledAndCappedScansHaveDistinctSafeReasons() {
        assertEquals(A0ProbeTermination.Reason.TIME_LIMIT,
            outcome(eos=false,expired=true))
        assertEquals(A0ProbeTermination.Reason.CANCELLED,
            outcome(eos=false,cancel=true))
        assertEquals(A0ProbeTermination.Reason.SAMPLE_CAP,
            outcome(eos=false,cap=true))
        assertEquals(A0ProbeTermination.Reason.NO_END_OF_STREAM,
            outcome(eos=false))
    }
}
