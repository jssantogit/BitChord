package com.music.bitchord.acquisition.a0

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/** No peer-error diagnostic may authorize transfer or weaken F token checks. */
class A0TransferFailureAdviceTest {
    @Test fun rejectsMalformedFOfferWithoutFallbackOrRetry() {
        assertEquals(
            A0TransferFailureAdvice.Kind.INVALID_F_OFFER,
            A0TransferFailureAdvice.classify(
                A0IndirectFileOfferWire.MalformedFileOffer(),
                A0ServerAssociatedTransfer.Phase.F_WAIT_STARTED
            )
        )
    }

    @Test fun separateServerAddressFailureFromFileChannelFailure() {
        assertEquals(
            A0TransferFailureAdvice.Kind.SERVER_ADDRESS_PROTOCOL,
            A0TransferFailureAdvice.classify(
                A0ServerAssociatedTransfer.PeerAddressFailure(
                    A0ServerAssociatedTransfer.AddressFailureKind.PROTOCOL_OR_IO, 2
                ), A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_REQUESTED
            )
        )
    }

    @Test fun distinguishPeerTimeoutFromSocketClosedDuringF() {
        assertEquals(
            A0TransferFailureAdvice.Kind.PEER_TIMEOUT,
            A0TransferFailureAdvice.classify(
                SocketTimeoutException(), A0ServerAssociatedTransfer.Phase.F_WAIT_STARTED
            )
        )
        assertEquals(
            A0TransferFailureAdvice.Kind.CONNECTION_INTERRUPTED,
            A0TransferFailureAdvice.classify(
                SocketException(), A0ServerAssociatedTransfer.Phase.BYTES_RECEIVED
            )
        )
    }

    @Test fun genericIoIsNotPresentedAsInvalidFOffer() {
        assertEquals(
            A0TransferFailureAdvice.Kind.OTHER_IO,
            A0TransferFailureAdvice.classify(
                IOException(), A0ServerAssociatedTransfer.Phase.F_WAIT_STARTED
            )
        )
    }
}
