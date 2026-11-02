package com.music.bitchord.acquisition.a0

import com.music.bitchord.acquisition.a0.A0ServerAssociatedTransfer.Phase
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class A0PreByteTransportRetryPolicyTest {
    @Test fun grwmTimeoutAfterQueueRequestMayTryOnlyEquivalentPeer() {
        assertTrue(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,SocketTimeoutException(),false,true))
        assertTrue(A0PreByteTransportRetryPolicy.canRetry(
            Phase.P_ESTABLISHED,SocketTimeoutException(),false,true))
    }

    @Test fun peerDisconnectBeforeAcceptanceCanUseIndependentEquivalentFallback() {
        assertTrue(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,java.io.EOFException(),false,true))
        assertTrue(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,java.net.SocketException("Connection reset"),false,true))
        assertFalse(A0PreByteTransportRetryPolicy.canRetry(
            Phase.TRANSFER_ACCEPTED,java.io.EOFException(),false,true))
        assertFalse(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,java.io.IOException("invalid protocol frame"),false,true))
    }

    @Test fun noRetryAfterTransferAcceptedOrAnyPossibleFileBytes() {
        for(phase in listOf(
            Phase.TRANSFER_ACCEPTED,Phase.F_WAIT_STARTED,
            Phase.F_ESTABLISHED,Phase.BYTES_RECEIVED)) {
            assertFalse(A0PreByteTransportRetryPolicy.canRetry(
                phase,SocketTimeoutException(),false,true))
        }
    }

    @Test fun noRetryOnUnknownPhaseAuthLossCancellationOrNonTimeout() {
        assertFalse(A0PreByteTransportRetryPolicy.canRetry(
            null,SocketTimeoutException(),false,true))
        assertFalse(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,SocketTimeoutException(),true,true))
        assertFalse(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,SocketTimeoutException(),false,false))
        assertFalse(A0PreByteTransportRetryPolicy.canRetry(
            Phase.QUEUE_UPLOAD_SENT,IOException("protocol"),false,true))
    }
}
