package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0PeerOfferGateTest {
    @Test fun acceptsMoreDistinctPeersWithoutAllowingRepeatedPOffersToWasteSlots() {
        val gate=A0PeerOfferGate(160)
        repeat(160) { index ->
            assertTrue(gate.accept("peer-$index",index+1))
            assertFalse(gate.accept("peer-$index",index+1))
        }
        assertEquals(160,gate.acceptedCount)
        assertEquals(160,gate.duplicateCount)
        assertFalse(gate.accept("extra",999))
        assertEquals(1,gate.capacityDropped)
    }
    @Test fun differentTicketsFromSamePeerRemainDistinct() {
        val gate=A0PeerOfferGate(160)
        assertTrue(gate.accept("peer",1))
        assertTrue(gate.accept("peer",2))
        assertEquals(2,gate.acceptedCount)
    }
    @Test fun unsubmittedOfferDoesNotConsumeThePeerBudgetOrSuppressRetry() {
        val gate=A0PeerOfferGate(8)
        repeat(8) { index -> assertTrue(gate.accept("peer-$index",index+1)) }
        assertFalse(gate.accept("new-peer",99))
        assertTrue(gate.releaseUnsubmitted("peer-3",4))
        assertEquals(7,gate.acceptedCount)
        assertTrue(gate.accept("peer-3",4))
        assertEquals(8,gate.acceptedCount)
        assertFalse(gate.releaseUnsubmitted("unknown",999))
        assertEquals(8,gate.acceptedCount)
    }

    @Test fun singlePeerCannotConsumeTheWholeIndirectSearchBudget() {
        val gate=A0PeerOfferGate(160)
        repeat(8) { assertTrue(gate.accept("noisy",it+1)) }
        assertFalse(gate.accept("noisy",9))
        assertEquals(1,gate.perPeerDropped)
        assertEquals(8,gate.acceptedCount)
        repeat(80) { assertTrue(gate.accept("other-$it",it+20)) }
        assertEquals(88,gate.acceptedCount)
        assertTrue(gate.releaseUnsubmitted("noisy",8))
        assertTrue(gate.accept("noisy",9))
        assertEquals(89,gate.acceptedCount)
        assertEquals(1,gate.perPeerDropped)
    }
}
