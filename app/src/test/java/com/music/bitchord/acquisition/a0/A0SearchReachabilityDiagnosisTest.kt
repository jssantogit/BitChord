package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0SearchReachabilityDiagnosisTest {
    private fun stage(direct:Int,offers:Int,connected:Int,replies:Int,choices:Int)=
        A0SearchReachabilityDiagnosis.classify(direct,offers,connected,replies,choices)
    @Test fun zeroResponsesAreNotMisdiagnosedAsRankingRejection() {
        assertEquals(A0SearchReachabilityDiagnosis.Stage.NO_PEER_ROUTES_OBSERVED,
            stage(0,0,0,0,0))
        assertEquals(A0SearchReachabilityDiagnosis.Stage.INDIRECT_OFFERS_ONLY,
            stage(0,3,0,0,0))
        assertEquals(A0SearchReachabilityDiagnosis.Stage.PEER_CONNECTION_WITHOUT_VALID_REPLY,
            stage(2,0,0,0,0))
        assertEquals(A0SearchReachabilityDiagnosis.Stage.PEER_CONNECTION_WITHOUT_VALID_REPLY,
            stage(0,3,2,0,0))
    }

    @Test fun receivedPeerRepliesCanStillFailToRetainAnyCandidate() {
        assertEquals(A0SearchReachabilityDiagnosis.Stage.VALID_REPLY_NO_RETAINED_CHOICES,
            stage(1,2,1,6,0))
        assertEquals(A0SearchReachabilityDiagnosis.Stage.RETAINED_CHOICES,
            stage(1,0,0,2,1))
    }
}
