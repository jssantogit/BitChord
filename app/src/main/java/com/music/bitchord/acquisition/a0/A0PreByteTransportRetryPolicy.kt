package com.music.bitchord.acquisition.a0

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * A timeout or definite socket disconnect may be retried against an independently discovered, metadata-
 * equivalent alternative ONLY while the P peer has not accepted an upload.
 * A timeout after TRANSFER_ACCEPTED may already have staged bytes; never
 * authorize another P/F through an inferred "no receipt" alone.
 *
 * Caller must hold the SAME A3 reservation and leave all new candidate
 * selection, authentication, technical probing and publication gates intact.
 */
internal object A0PreByteTransportRetryPolicy {
    fun canRetry(
        lastPhase:A0ServerAssociatedTransfer.Phase?,
        error:Exception,
        cancelled:Boolean,
        sameGeneration:Boolean,
    ):Boolean {
        if(cancelled || !sameGeneration ||
            error !is SocketTimeoutException &&
            error !is EOFException && error !is SocketException)
            return false
        return lastPhase in setOf(
            A0ServerAssociatedTransfer.Phase.GATE_PASSED,
            A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_REQUESTED,
            A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_RESOLVED,
            A0ServerAssociatedTransfer.Phase.P_DIRECT_ATTEMPT,
            A0ServerAssociatedTransfer.Phase.P_INDIRECT_REQUESTED,
            A0ServerAssociatedTransfer.Phase.P_ESTABLISHED,
            A0ServerAssociatedTransfer.Phase.QUEUE_UPLOAD_SENT,
        )
    }
}
