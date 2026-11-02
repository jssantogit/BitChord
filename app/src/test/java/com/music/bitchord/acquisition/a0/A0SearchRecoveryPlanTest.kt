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
    @Test fun longerDescriptiveTitlePrecedesCompilationAlbumVariant() {
        val original="BTS (방탄소년단) - Boy In Luv"
        val recovery=A0SearchRecoveryPlan(original,album="Proof")
        val already=mutableSetOf(A0SearchQuery.normalize(original))
        assertEquals("Boy In Luv",recovery.next(6000,false,already))
        already.add("Boy In Luv")
        assertEquals(A0SearchQuery.normalize(
            "BTS (방탄소년단) Proof Boy In Luv"),recovery.next(12000,false,already))
    }
    @Test fun releaseWideFallbackSearchesAlbumToRecoverOtherTracksWithoutTransfer() {
        val recovery=A0SearchRecoveryPlan(
            "BTS (방탄소년단) - Boy In Luv",album="Proof",releaseWide=true)
        val sent=mutableSetOf(A0SearchQuery.normalize(
            "BTS (방탄소년단) - Boy In Luv"))
        assertEquals("BTS Proof",recovery.next(6000,false,sent))
        sent.add("BTS Proof")
        assertEquals("Boy In Luv",recovery.next(12000,false,sent))
        assertFalse(recovery.authorizesTransfer)
        val generic=A0SearchRecoveryPlan("BTS - Normal","ARIRANG",releaseWide=true)
        assertEquals("BTS ARIRANG",generic.next(6000,false,setOf("BTS Normal")))
        val noAlbum=A0SearchRecoveryPlan("BTS - Normal","",releaseWide=true)
        assertEquals("Normal",noAlbum.next(6000,false,setOf("BTS Normal")))
    }

    @Test fun genericTrackReceivesAlbumSpecificRecoveryBeforeBroadTitleOnly() {
        val recovery=A0SearchRecoveryPlan("BTS - Normal",album="ARIRANG")
        val already=mutableSetOf("BTS Normal")
        assertNull(recovery.next(1000,false,already))
        val first=recovery.next(6000,false,already)
        assertEquals("BTS ARIRANG Normal",first)
        already.add(checkNotNull(first))
        assertEquals("Normal",recovery.next(12000,false,already))
        assertNull(recovery.next(12000,true,already))
        assertFalse(recovery.authorizesTransfer)
    }

    @Test fun invalidAlbumNeverProducesUnboundedOrCrossReleaseSearch() {
        val original="ILLIT - More"
        for(album in listOf("", " ", "../", "X".repeat(140), "MoRE")) {
            val next=A0SearchRecoveryPlan(original,album=album)
                .next(6000,false,setOf("ILLIT More"))
            assertEquals("More",next)
        }
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
