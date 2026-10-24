package com.music.bitchord.acquisition.a0

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Physical regression: an F upload took longer than A0's 15-second P/F negotiation. */
class A0TransferDeadlinesTest {
    private val seconds=TimeUnit.SECONDS

    @Test fun finishingNegotiationNearFifteenSecondsStillAllowsFileBytesToArrive() {
        val started=seconds.toNanos(10)
        val negotiation=A0TransferDeadlines.negotiate(started)
        val fEstablished=started+seconds.toNanos(14)
        assertFalse(A0TransferDeadlines.isExpired(negotiation,fEstablished))
        val receiving=A0TransferDeadlines.receive(fEstablished)
        assertTrue("F receive must not reuse the P negotiation deadline",
            receiving>negotiation)
        assertFalse("16 seconds of file data must not fail at old negotiation limit",
            A0TransferDeadlines.isExpired(receiving,fEstablished+seconds.toNanos(16)))
        assertTrue("F still must expire; not unbounded",
            A0TransferDeadlines.isExpired(receiving,fEstablished+seconds.toNanos(181)))
    }

    @Test fun peerNegotiationDoesNotGetAnUnboundedExtension() {
        val started=seconds.toNanos(10)
        val deadline=A0TransferDeadlines.negotiate(started)
        assertFalse(A0TransferDeadlines.isExpired(deadline,started+seconds.toNanos(14)))
        assertTrue(A0TransferDeadlines.isExpired(deadline,started+seconds.toNanos(15)))
    }

    @Test fun receivingIsBoundedIndependentlyOfLengthOfAlreadyCompletedNegotiation() {
        val started=seconds.toNanos(10)
        val fEstablished=started+seconds.toNanos(6)
        val receiveDeadline=A0TransferDeadlines.receive(fEstablished)
        assertTrue(A0TransferDeadlines.isExpired(receiveDeadline,
            fEstablished+seconds.toNanos(180)))
    }
}
