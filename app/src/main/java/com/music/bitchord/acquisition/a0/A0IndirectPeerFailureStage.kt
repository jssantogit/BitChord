package com.music.bitchord.acquisition.a0

/**
 * Sanitized classification of a failed indirect P search route.
 * A response failure never implies an incorrect song or server throttling.
 */
internal object A0IndirectPeerFailureStage {
    enum class Phase { CONNECT, INIT, RESPONSE }

    fun classify(tcpConnected:Boolean,peerInitSent:Boolean):Phase=when {
        !tcpConnected -> Phase.CONNECT
        !peerInitSent -> Phase.INIT
        else -> Phase.RESPONSE
    }
}
