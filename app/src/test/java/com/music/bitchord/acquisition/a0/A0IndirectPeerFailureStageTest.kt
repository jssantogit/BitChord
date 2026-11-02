package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0IndirectPeerFailureStageTest {
    @Test fun connectionHandshakeAndReplyFailuresHaveDisjointDiagnostics() {
        assertEquals(A0IndirectPeerFailureStage.Phase.CONNECT,
            A0IndirectPeerFailureStage.classify(false,false))
        assertEquals(A0IndirectPeerFailureStage.Phase.INIT,
            A0IndirectPeerFailureStage.classify(true,false))
        assertEquals(A0IndirectPeerFailureStage.Phase.RESPONSE,
            A0IndirectPeerFailureStage.classify(true,true))
    }

    @Test fun impossiblePeerInitWithoutTcpNeverMasqueradesAsReplyFailure() {
        assertEquals(A0IndirectPeerFailureStage.Phase.CONNECT,
            A0IndirectPeerFailureStage.classify(false,true))
    }
}
