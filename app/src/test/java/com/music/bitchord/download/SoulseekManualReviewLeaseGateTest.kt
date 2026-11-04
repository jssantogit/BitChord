package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class SoulseekManualReviewLeaseGateTest {
    @Test fun recreatedActivityCannotClaimSameIntentTwice() {
        val gate=SoulseekManualReviewLeaseGate()
        val token=gate.reserve("auto-session")!!
        assertTrue(gate.hasOpen)
        assertTrue(gate.claim("auto-session",token))
        assertFalse("same Intent must not get a new A3 lease",
            gate.claim("auto-session",token))
        assertTrue(gate.hasOpen)
        assertTrue(gate.close("auto-session",token))
        assertFalse(gate.hasOpen)
    }

    @Test fun forgedOrStaleTokenCannotClaimOrReleaseActiveWorker() {
        val gate=SoulseekManualReviewLeaseGate()
        val token=gate.reserve("auto-session")!!
        assertFalse(gate.claim("auto-session","forged"))
        assertFalse(gate.close("auto-session","forged"))
        assertFalse(gate.claim("other-session",token))
        assertFalse(gate.close("other-session",token))
        assertTrue(gate.claim("auto-session",token))
        assertFalse(gate.reserve("other-session")!=null)
        assertFalse(gate.close("auto-session","forged"))
        assertTrue(gate.hasOpen)
    }

    @Test fun oldTokenCannotReleaseNewReviewForSameSession() {
        val gate=SoulseekManualReviewLeaseGate()
        val first=gate.reserve("auto-session")!!
        assertTrue(gate.claim("auto-session",first))
        assertTrue(gate.close("auto-session",first))
        val second=gate.reserve("auto-session")!!
        assertNotEquals(first,second)
        assertFalse(gate.claim("auto-session",first))
        assertFalse(gate.close("auto-session",first))
        assertTrue(gate.claim("auto-session",second))
        assertTrue(gate.close("auto-session",second))
        assertFalse(gate.hasOpen)
    }

    @Test fun reviewedCandidateReadsRequireClaimedLiveToken() {
        val gate=SoulseekManualReviewLeaseGate()
        val token=gate.reserve("s")!!
        assertFalse(gate.ownsClaimed("s",token))
        assertTrue(gate.claim("s",token))
        assertTrue(gate.ownsClaimed("s",token))
        assertFalse(gate.ownsClaimed("s","wrong"))
        assertFalse(gate.ownsClaimed("other",token))
        assertTrue(gate.close("s",token))
        assertFalse(gate.ownsClaimed("s",token))
    }

    @Test fun invalidSessionCannotReserve() {
        val gate=SoulseekManualReviewLeaseGate()
        assertNull(gate.reserve(""))
        assertNull(gate.reserve(" "))
        assertFalse(gate.hasOpen)
    }
}
