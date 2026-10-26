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
}
