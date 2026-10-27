package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0SearchWindowEvidenceTest {
    @Test fun separatesRepliesInsideWindowFromThoseAcceptedDuringDrain() {
        val shadow=A0SearchWindowEvidence()
        shadow.record(6,12,8)
        shadow.record(12,31,14)
        shadow.record(18,59,20)
        val report=shadow.finish(65,22)
        assertTrue(report.checkpointsComplete)
        assertEquals(28,report.netRepliesAfter12)
        assertEquals(6,report.netClaimsAfter12)
        assertEquals(6,report.netClaims6To12)
        assertEquals(59,report.at18.replies)
        assertEquals(65,report.afterDrain.replies)
        assertEquals(6,report.repliesDuringDrain)
        assertEquals(2,report.claimsDuringDrain)
    }

    @Test fun plateauAtTwelveDoesNotEstablishCompleteness() {
        val shadow=A0SearchWindowEvidence()
        shadow.record(6,15,6)
        shadow.record(12,15,6)
        shadow.record(18,30,11)
        val report=shadow.finish(31,12)
        assertEquals(0,report.netClaims6To12)
        assertEquals(5,report.netClaimsAfter12)
        assertEquals(1,report.claimsDuringDrain)
    }

    @Test fun missingEndCheckpointDoesNotMislabelTheDrainAsSecond18() {
        val shadow=A0SearchWindowEvidence()
        shadow.record(6,2,1)
        shadow.record(12,3,1)
        val report=shadow.finish(9,4)
        assertTrue(report.hasBothCheckpoints)
        assertFalse(report.hasWindowEndCheckpoint)
        assertFalse(report.checkpointsComplete)
        assertEquals(0,report.at18.replies)
        assertEquals(9,report.afterDrain.replies)
        assertEquals(0,report.netRepliesAfter12)
    }

    @Test(expected=IllegalArgumentException::class)
    fun checkpointsMustBeExplicitlyTimed() {
        A0SearchWindowEvidence().record(10,1,1)
    }
}
