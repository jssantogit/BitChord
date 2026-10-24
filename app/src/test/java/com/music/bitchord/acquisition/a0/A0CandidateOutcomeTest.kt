package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Regression: indirect valid P2P reply with 0 candidates is NOT no_peer_reply. */
class A0CandidateOutcomeTest {
    private fun report(replies:Int,accepted:Int)=A0ObservedCandidateHandoff.Screening(
        responses=replies,publicFiles=8,lockedFiles=0,tooLarge=4,
        unsupportedFormat=3,unsafePath=0,noSlot=1,accepted=accepted
    )
    @Test fun indirectValidReplyWithZeroCandidatesIsScreenedOut() {
        assertEquals(A0CandidateOutcome.Kind.NO_ELIGIBLE_CANDIDATE,
            A0CandidateOutcome.classify(report(1,0),validatedPeerReply=true))
    }
    @Test fun noReplyIsActuallyNoReply() {
        assertEquals(A0CandidateOutcome.Kind.NO_PEER_REPLY,
            A0CandidateOutcome.classify(report(0,0),validatedPeerReply=false))
    }
    @Test fun brokenServerStreamMustNotBeReportedAsSilentPeer() {
        assertEquals(A0CandidateOutcome.Kind.SERVER_READER_FAILURE,
            A0CandidateOutcome.classify(report(0,0),
                validatedPeerReply=false,serverReaderFailed=true))
        assertEquals(A0CandidateOutcome.Kind.SERVER_READER_FAILURE,
            A0CandidateOutcome.classify(report(1,1),
                validatedPeerReply=true,serverReaderFailed=true))
    }
    @Test fun selectedCandidateIsReady() {
        assertEquals(A0CandidateOutcome.Kind.CANDIDATES_READY,
            A0CandidateOutcome.classify(report(1,1),validatedPeerReply=true))
    }
}
