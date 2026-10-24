package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Stable UI-level diagnosis from a P/F exception, not a retry/fallback policy.
 * Never includes untrusted network payloads or relaxes F-channel validation.
 */
internal object A0TransferFailureAdvice {
    enum class Kind {
        INVALID_F_OFFER, SERVER_ADDRESS_PROTOCOL, PEER_TIMEOUT,
        CONNECTION_INTERRUPTED, OTHER_IO
    }

    fun classify(error: Throwable, phase: A0ServerAssociatedTransfer.Phase?): Kind = when {
        error is A0IndirectFileOfferWire.MalformedFileOffer -> Kind.INVALID_F_OFFER
        error is A0ServerAssociatedTransfer.PeerAddressFailure &&
            error.kind == A0ServerAssociatedTransfer.AddressFailureKind.PROTOCOL_OR_IO ->
            Kind.SERVER_ADDRESS_PROTOCOL
        error is SocketTimeoutException -> Kind.PEER_TIMEOUT
        error is SocketException -> Kind.CONNECTION_INTERRUPTED
        else -> Kind.OTHER_IO
    }
}
