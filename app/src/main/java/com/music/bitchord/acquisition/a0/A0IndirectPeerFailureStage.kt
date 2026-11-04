package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Sanitized classification of a failed indirect P search route.
 * A response failure never implies an incorrect song or server throttling.
 */
internal object A0IndirectPeerFailureStage {
    enum class Phase { CONNECT, INIT, RESPONSE }
    enum class ConnectCause { TIMEOUT, CONNECT_ERROR, NO_ROUTE, OTHER }

    /** ConnectException alone cannot prove refusal: Android also uses it for timeouts.
     * Reason classes only; never serialize exception messages or peer addresses. */
    fun connectCause(error:IOException):ConnectCause {
        var current:Throwable?=error
        repeat(5) {
            when(current) {
                is SocketTimeoutException -> return ConnectCause.TIMEOUT
                is NoRouteToHostException -> return ConnectCause.NO_ROUTE
                is ConnectException -> return ConnectCause.CONNECT_ERROR
            }
            current=current?.cause
        }
        return ConnectCause.OTHER
    }

    fun classify(tcpConnected:Boolean,peerInitSent:Boolean):Phase=when {
        !tcpConnected -> Phase.CONNECT
        !peerInitSent -> Phase.INIT
        else -> Phase.RESPONSE
    }
}
