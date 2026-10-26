package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Per-search anonymous source evidence, not a durable peer reputation store. */
class A0PeerReliabilityEvidenceTest {
    private val ledger=A0PeerReliabilityEvidence()
    @Test fun observedSuccessOutranksRepeatedReadTimeoutsButNeverOverridesMusicalIdentity() {
        ledger.observe("weak",A0PeerReliabilityEvidence.Outcome.READ_TIMEOUT)
        ledger.observe("weak",A0PeerReliabilityEvidence.Outcome.READ_TIMEOUT)
        ledger.observe("strong",A0PeerReliabilityEvidence.Outcome.REPLY_VALID)
        assertTrue(ledger.score("strong")>ledger.score("weak"))
        assertEquals(0,ledger.score("new"))
        assertFalse(ledger.grantsTransfer)
    }
    @Test fun cancellationDoesNotPenalizePeerAndEvidenceStaysBounded() {
        repeat(100) { ledger.observe("a",A0PeerReliabilityEvidence.Outcome.CANCELLED) }
        assertEquals(0,ledger.score("a"))
        repeat(100) { ledger.observe("a",A0PeerReliabilityEvidence.Outcome.READ_TIMEOUT) }
        assertEquals(A0PeerReliabilityEvidence.MAX_EVENTS_PER_PEER,
            ledger.observedCount("a"))
        assertTrue(ledger.score("a")<0)
    }
    @Test fun fallbackSkipsKnownBrokenSourcesPrefersReadyOnlyForSameClaim() {
        ledger.observe("bad",A0PeerReliabilityEvidence.Outcome.READ_TIMEOUT)
        ledger.observe("bad",A0PeerReliabilityEvidence.Outcome.CONNECT_FAILED)
        val offers=listOf(
            A0PeerReliabilityEvidence.Alternative("bad","same",A0PeerAvailability.Tier.READY),
            A0PeerReliabilityEvidence.Alternative("good","same",A0PeerAvailability.Tier.READY),
            A0PeerReliabilityEvidence.Alternative("other","different",A0PeerAvailability.Tier.READY),
            A0PeerReliabilityEvidence.Alternative("waiting","same",A0PeerAvailability.Tier.CONGESTED))
        val chosen=ledger.sequentialFallbacks(offers,claimKey="same",failedPeers=setOf("origin"),maxPeers=2)
        assertEquals(listOf("good","waiting"),chosen)
    }
    @Test fun duplicatePeerNeverStartsTwoTransfers() {
        val offers=(1..10).map {
            A0PeerReliabilityEvidence.Alternative("one","same",A0PeerAvailability.Tier.READY)
        }+A0PeerReliabilityEvidence.Alternative("two","same",A0PeerAvailability.Tier.UNKNOWN)
        assertEquals(listOf("one","two"),ledger.sequentialFallbacks(
            offers,claimKey="same",failedPeers=emptySet(),maxPeers=3))
    }
    @Test(timeout=5000) fun thousandsOfObservationsDoNotRetainUnboundedPeers() {
        repeat(100_000) { ledger.observe("p-${it%1200}",A0PeerReliabilityEvidence.Outcome.READ_TIMEOUT) }
        assertTrue(ledger.peerCount<=A0PeerReliabilityEvidence.MAX_PEERS)
        assertTrue(ledger.totalEvents<=A0PeerReliabilityEvidence.MAX_PEERS*
            A0PeerReliabilityEvidence.MAX_EVENTS_PER_PEER)
        assertFalse(ledger.toString().contains("p-"))
    }
}
