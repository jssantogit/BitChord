package com.music.bitchord.acquisition.a0

import java.net.Socket
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Manual choice: no static CC0 filename, no unrequested transfers. */
class A0ObservedCandidateHandoffTest {
    @Test fun collectsEligiblePublicAudioCandidatesWithoutRequiringRingingsound() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"ouruser",81,true)
        val response=A0SoulseekSearchResponse(
            peerUsername="somepeer",token=81,
            files=listOf(
                A0SoulseekSearchFile("Songs\\newjeans - omg.mp3",40000,"mp3",emptyMap()),
                A0SoulseekSearchFile("Songs\\other track.flac",110000,"flac",emptyMap()),
                A0SoulseekSearchFile("../invalid.mp3",80000,"mp3",emptyMap()),
                A0SoulseekSearchFile("Songs\\oversize.mp3",17L*1024*1024,"mp3",emptyMap())
            ),
            lockedFiles=emptyList(),hasFreeUploadSlot=true,uploadSpeed=1000,queueLength=0
        )
        handoff.offerParsed(response,"somepeer")
        val choices=handoff.choices()
        assertEquals(2,choices.size)
        assertTrue(choices[0].displayName.contains("newjeans - omg.mp3"))
        assertEquals(40000L,choices[0].sizeBytes)
        assertFalse(handoff.toString().contains("somepeer"))
    }

    @Test fun rejectsWrongTokenPeerLockedNoSlotAndInvalidExtension() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"ouruser",81,true)
        val song=A0SoulseekSearchFile("Music\\valid.mp3",1200,"mp3",emptyMap())
        fun response(token:Int=81,free:Boolean=true,files:List<A0SoulseekSearchFile> =
            listOf(song), locked:List<A0SoulseekSearchFile> = emptyList()) =
            A0SoulseekSearchResponse("somepeer",token,files,locked,free,1000,0)
        handoff.offerParsed(response(token=82),"somepeer")
        handoff.offerParsed(response(),"wrongpeer")
        handoff.offerParsed(response(free=false),"somepeer")
        handoff.offerParsed(response(files=emptyList(),locked=listOf(song)),"somepeer")
        handoff.offerParsed(response(files=listOf(
            A0SoulseekSearchFile("Music\\evil.exe",1200,"exe",emptyMap())
        )),"somepeer")
        assertTrue(handoff.choices().isEmpty())
    }

    @Test fun cannotTransferBeforeReadersStopOrAfterCancellationOrWithBadSelection() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"ouruser",81,true)
        handoff.offerParsed(A0SoulseekSearchResponse("somepeer",81,
            listOf(A0SoulseekSearchFile("Music\\valid.mp3",1200,"mp3",emptyMap())),
            emptyList(),true,1000,0),"somepeer")
        try {handoff.transferChoice(0,java.io.File("."))
            fail("Cannot transfer while server read thread still active")}
        catch (_:IllegalStateException){}
        handoff.markReadersStopped()
        try {handoff.transferChoice(10,java.io.File("."))
            fail("Invalid manual selection must fail")}
        catch (_:IllegalArgumentException){}
        handoff.cancel()
        try {handoff.transferChoice(0,java.io.File("."))
            fail("Cancelled")}
        catch (_:IOException){}
    }
}
