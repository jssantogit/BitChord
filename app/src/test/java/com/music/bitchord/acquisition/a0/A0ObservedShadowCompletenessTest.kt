package com.music.bitchord.acquisition.a0

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0ObservedShadowCompletenessTest {
    @Test fun contradictoryDuplicatePeerPathMustFlagLostEvidenceBeforeDedup() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,collectReleaseShadow=true,
            releaseQueries=listOf("ILLIT - Magnetic"))
        val file="Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Magnetic.mp3"
        val first=A0SoulseekSearchFile(file,6_440_000L,"mp3",mapOf(0 to 320,1 to 161))
        val changed=A0SoulseekSearchFile(file,6_440_000L,"mp3",mapOf(0 to 256,1 to 161))
        assertTrue(handoff.offerParsed(A0SoulseekSearchResponse(
            "peer",177,listOf(first),emptyList(),true,128_000,0),"peer"))
        assertTrue(handoff.offerParsed(A0SoulseekSearchResponse(
            "peer",177,listOf(changed),emptyList(),true,128_000,0),"peer"))
        assertEquals(1,handoff.releaseShadowSnapshot().choices.size)
        assertEquals(setOf(0),handoff.releaseShadowSnapshot().truncatedSlots)
    }

    @Test fun perPeerShadowCapMustMarkAffectedReleaseSlotAsIncomplete() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,collectReleaseShadow=true,
            releaseQueries=listOf("ILLIT - Magnetic","ILLIT - My World"))
        val files=(0..64).map { index ->
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Magnetic $index.mp3",
                6_440_000L,"mp3",mapOf(0 to 320,1 to 161))
        }
        assertTrue(handoff.offerParsed(A0SoulseekSearchResponse(
            "peer",177,files,emptyList(),true,128_000,0),"peer"))
        val sample=handoff.releaseShadowSnapshot()
        assertEquals(64,sample.choices.size)
        assertEquals(setOf(0),sample.truncatedSlots)
    }
}
