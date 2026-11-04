package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
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

    @Test fun connectFailuresRetainDistinctTimeoutConnectExceptionRouteAndOtherReasons() {
        val causes=A0IndirectPeerFailureStage
        assertEquals(A0IndirectPeerFailureStage.ConnectCause.TIMEOUT,
            causes.connectCause(SocketTimeoutException("peer address intentionally not recorded")))
        assertEquals(A0IndirectPeerFailureStage.ConnectCause.CONNECT_ERROR,
            causes.connectCause(ConnectException("refused")))
        // A bare ConnectException is not proof of ECONNREFUSED on Android.
        assertEquals(A0IndirectPeerFailureStage.ConnectCause.CONNECT_ERROR,
            causes.connectCause(ConnectException("Connection timed out")))
        assertEquals(A0IndirectPeerFailureStage.ConnectCause.NO_ROUTE,
            causes.connectCause(NoRouteToHostException("unreachable")))
        assertEquals(A0IndirectPeerFailureStage.ConnectCause.OTHER,
            causes.connectCause(IOException("unknown")))
    }

    @Test fun wrappedNetworkTimeoutStillCountsAsTimeoutWithoutLoggingDetails() {
        assertEquals(A0IndirectPeerFailureStage.ConnectCause.TIMEOUT,
            A0IndirectPeerFailureStage.connectCause(IOException("wrapper",
                IOException("nested",SocketTimeoutException("timeout")))))
    }

    @Test fun impossiblePeerInitWithoutTcpNeverMasqueradesAsReplyFailure() {
        assertEquals(A0IndirectPeerFailureStage.Phase.CONNECT,
            A0IndirectPeerFailureStage.classify(false,true))
    }
}
