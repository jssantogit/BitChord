package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0SearchWindowEvidenceTest {
    @Test fun lateOffersRemainVisibleInShadowWithoutGrantingEarlyStop() {
        val shadow=A0SearchWindowEvidence()
        shadow.record(6,12,8)
        shadow.record(12,31,14)
        val report=shadow.finish(59,20)
        assertTrue(report.hasBothCheckpoints)
        assertEquals(28,report.netRepliesAfter12)
        assertEquals(6,report.netClaimsAfter12)
        assertEquals(6,report.netClaims6To12)
    }

    @Test fun plateauAtTwelveDoesNotGuaranteeThereAreNoLateOffers() {
        val shadow=A0SearchWindowEvidence()
        shadow.record(6,15,6)
        shadow.record(12,15,6)
        val report=shadow.finish(30,11)
        assertEquals(0,report.netClaims6To12)
        assertEquals(5,report.netClaimsAfter12)
    }

    @Test fun missingCheckpointNeverMasqueradesAsMeasuredCoverage() {
        val shadow=A0SearchWindowEvidence()
        val report=shadow.finish(4,2)
        assertFalse(report.hasBothCheckpoints)
        assertEquals(0,report.netRepliesAfter12)
        assertEquals(2,report.at18.claims)
    }

    @Test(expected=IllegalArgumentException::class)
    fun checkpointsMustBeExplicitlyTimed() {
        A0SearchWindowEvidence().record(10,1,1)
    }
}
