package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A0SearchRecoveryPlanTest {
    @Test fun accentedArtistUsesAsciiBeforeBoundedTitleOnlyRecovery() {
        val recovery=A0SearchRecoveryPlan("Björk - Jóga")
        val already=mutableSetOf("Björk Jóga")
        assertNull(recovery.next(1000,hasRelevantCandidate=false,alreadySent=already))
        val first=recovery.next(6000,false,already)
        assertEquals("Bjork Joga",first)
        already.add(checkNotNull(first))
        assertEquals("Jóga",recovery.next(12000,false,already))
        already.add("Jóga")
        assertNull(recovery.next(15000,false,already))
    }
    @Test fun ordinarySongGetsOneAdditionalSearchNotRepeatedFlood() {
        val recovery=A0SearchRecoveryPlan("ILLIT - Magnetic")
        val sent=mutableSetOf("ILLIT Magnetic")
        assertEquals("Magnetic",recovery.next(6000,false,sent))
        sent.add("Magnetic")
        assertNull(recovery.next(12000,false,sent))
        assertNull(recovery.next(6000,true,mutableSetOf("ILLIT Magnetic")))
    }
    @Test fun recoveryRespectsOriginalClockAndInvalidShortVariant() {
        val recovery=A0SearchRecoveryPlan("Artist - Go")
        assertNull(recovery.next(6000,false,setOf("Artist Go")))
        assertNull(A0SearchRecoveryPlan("ILLIT - Magnetic").next(
            17000,false,setOf("ILLIT Magnetic")))
        assertFalse(recovery.authorizesTransfer)
    }
    @Test fun noUnexpectedVariationOnUnstructuredOrNoLongerRelevantQuery() {
        val unstructured=A0SearchRecoveryPlan("magnetic")
        assertNull(unstructured.next(6000,false,setOf("magnetic")))
        assertNull(A0SearchRecoveryPlan("Bjork - Joga").next(
            6000,true,setOf("Bjork Joga")))
        assertFalse(unstructured.toString().contains("magnetic"))
    }
}
