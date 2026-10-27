package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Remote conditions are unverified hints, never evidence of musical identity. */
class A0PeerAvailabilityTest {
    @Test fun healthyPeerWithFreeSlotIsReady() {
        val a=A0PeerAvailability.assess(true,false,queueLength=0,uploadSpeedBytesPerSecond=192_000)
        assertEquals(A0PeerAvailability.Tier.READY,a.tier)
        assertTrue(a.reasons.isEmpty())
    }

    @Test fun missingSpeedIsUnknownNotARejection() {
        val a=A0PeerAvailability.assess(true,false,queueLength=0,uploadSpeedBytesPerSecond=0)
        assertEquals(A0PeerAvailability.Tier.UNKNOWN,a.tier)
        assertTrue(A0PeerAvailability.Reason.SPEED_UNKNOWN in a.reasons)
    }

    @Test fun congestedOrSlowPeerIsClassifiedSeparately() {
        assertEquals(A0PeerAvailability.Tier.CONGESTED,
            A0PeerAvailability.assess(true,false,queueLength=40,uploadSpeedBytesPerSecond=256_000).tier)
        assertEquals(A0PeerAvailability.Tier.CONGESTED,
            A0PeerAvailability.assess(true,false,queueLength=0,uploadSpeedBytesPerSecond=4_096).tier)
    }

    @Test fun negativeOrNonsensicalMetricsNeverBecomeReady() {
        assertEquals(A0PeerAvailability.Tier.UNKNOWN,
            A0PeerAvailability.assess(true,false,queueLength=-1,uploadSpeedBytesPerSecond=-1).tier)
    }

    @Test fun unavailablePeerOrLockedFileHasHardAvailabilityGate() {
        assertEquals(A0PeerAvailability.Tier.UNAVAILABLE,
            A0PeerAvailability.assess(false,false,0,128_000).tier)
        assertEquals(A0PeerAvailability.Tier.UNAVAILABLE,
            A0PeerAvailability.assess(true,true,0,128_000).tier)
    }

    @Test fun availabilityCannotChangeCandidateIdentityOrMusicalScore() {
        val slow=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT - Magnetic.mp3",320,
            queueLength=120, declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000L)
        val fast=A0CandidateRank.rank("ILLIT - Magnetic","ILLIT - Magnetic.mp3",320,
            queueLength=0, declaredDurationSeconds=161,expectedDurationSeconds=161,
            sizeBytes=6_400_000L)
        assertEquals(fast,slow)
    }

    @Test fun readinessSortOnlyAfterMusicalEvidence() {
        val bestMusic=A0PeerAvailability.assess(true,false,40,32_000)
        val fast=A0PeerAvailability.assess(true,false,0,250_000)
        assertEquals(A0PeerAvailability.Tier.CONGESTED,bestMusic.tier)
        assertTrue(A0PeerAvailability.priority(fast)>A0PeerAvailability.priority(bestMusic))
    }

    @Test fun withinSameAvailabilityTierPreferShorterKnownQueueBeforeClaimedSpeed() {
        val short=A0PeerAvailability.assess(true,false,0,80_000)
        val long=A0PeerAvailability.assess(true,false,2,800_000)
        assertEquals(A0PeerAvailability.Tier.READY,short.tier)
        assertEquals(A0PeerAvailability.Tier.READY,long.tier)
        assertTrue(A0PeerAvailability.compareSource(short,long)>0)
        assertTrue(A0PeerAvailability.compareSource(long,short)<0)
    }

    @Test fun withinSameQueuePreferHigherAdvertisedUploadRate() {
        val faster=A0PeerAvailability.assess(true,false,1,256_000)
        val slower=A0PeerAvailability.assess(true,false,1,80_000)
        assertTrue(A0PeerAvailability.compareSource(faster,slower)>0)
        assertEquals(0,A0PeerAvailability.compareSource(faster,faster))
    }
}
