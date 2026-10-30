package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0AutoRetrySequencerTest {
    @Test fun noConsentMeansZeroAttemptsEvenWhenPeersLookStrong() {
        val defaultOff=A0AutoRetrySequencer(3,equivalentClaimsConfirmed=true)
        assertNull(defaultOff.begin())
        assertEquals(0,defaultOff.attemptsStarted)
        val unproven=A0AutoRetrySequencer(3,optIn=true)
        assertNull(unproven.begin())
        assertFalse(unproven.canTransfer)
    }

    @Test fun threeMetadataEquivalentPeersAreTriedStrictlySequentially() {
        val policy=A0AutoRetrySequencer(3,optIn=true,equivalentClaimsConfirmed=true)
        val first=policy.begin()!!
        assertEquals(0,first.alternativeIndex)
        assertNull("Cannot race two peers for one track",policy.begin())
        assertTrue(policy.complete(first,success=false))
        val second=policy.begin()!!
        assertEquals(1,second.alternativeIndex)
        assertFalse("Stale completion cannot finish another peer",policy.complete(first,true))
        assertNull(policy.begin())
        assertTrue(policy.complete(second,false))
        val third=policy.begin()!!
        assertEquals(2,third.alternativeIndex)
        assertTrue(policy.complete(third,false))
        assertNull("Retries must end at exactly three",policy.begin())
        assertEquals(3,policy.attemptsStarted)
        assertTrue(policy.isComplete)
        assertFalse(policy.canTransfer)
    }

    @Test fun successAndCancellationNeverStartAnotherAttempt() {
        val success=A0AutoRetrySequencer(3,true,true)
        val attempt=success.begin()!!
        assertTrue(success.complete(attempt,true))
        assertTrue(success.isComplete)
        assertNull(success.begin())
        val cancelled=A0AutoRetrySequencer(3,true,true)
        val pending=cancelled.begin()!!
        cancelled.cancel()
        assertFalse(cancelled.complete(pending,true))
        assertNull(cancelled.begin())
        assertTrue(cancelled.isComplete)
        assertFalse(cancelled.toString().contains("peer"))
    }

    @Test fun zeroOrInvalidPeerBudgetsNeverBecomesUnboundedRetry() {
        assertNull(A0AutoRetrySequencer(0,true,true).begin())
        for(n in listOf(-1,4,1000)) {
            try {
                A0AutoRetrySequencer(n,true,true)
                fail("Invalid retry budget must fail")
            }catch(_:IllegalArgumentException) {}
        }
    }
}
