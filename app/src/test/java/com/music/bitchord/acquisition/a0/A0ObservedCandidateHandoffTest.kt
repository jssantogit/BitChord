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
                A0SoulseekSearchFile("Songs\\oversize.mp3",257L*1024*1024,"mp3",emptyMap())
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
    @Test fun acceptsRealisticThirtyMegabyteFlacAndMissingExtensionMetadata() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"ouruser",81,true)
        val large=A0SoulseekSearchFile("Albums\\long-track.flac",
            30L*1024L*1024L,"FLAC",emptyMap())
        val missing=A0SoulseekSearchFile("Albums\\short-track.mp3",
            6L*1024L*1024L,"",emptyMap())
        handoff.offerParsed(A0SoulseekSearchResponse("somepeer",81,
            listOf(large,missing),emptyList(),true,1000,0),"somepeer")
        assertEquals(2,handoff.choices().size)
        assertEquals(2,handoff.screening().accepted)
        assertEquals(2,handoff.screening().publicFiles)
    }

    @Test fun screeningRevealsWhyValidRepliesProduceZeroCandidatesWithoutPeerPaths() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"ouruser",81,true)
        fun response(files:List<A0SoulseekSearchFile>,free:Boolean=true,
            locked:List<A0SoulseekSearchFile> = emptyList())=
            A0SoulseekSearchResponse("somepeer",81,files,locked,free,1000,0)
        val audio=A0SoulseekSearchFile("Album\\sample.mp3",5L*1024*1024,"mp3",emptyMap())
        handoff.offerParsed(response(listOf(audio),free=false),"somepeer")
        handoff.offerParsed(response(listOf(
            A0SoulseekSearchFile("Album\\huge.flac",257L*1024*1024,"flac",emptyMap()),
            A0SoulseekSearchFile("Album\\cover.jpg",20000,"jpg",emptyMap()),
            A0SoulseekSearchFile("../escape.mp3",20000,"mp3",emptyMap())
        ),locked=listOf(audio)),"somepeer")
        val report=handoff.screening()
        assertEquals(2,report.responses)
        assertEquals(4,report.publicFiles)
        assertEquals(1,report.lockedFiles)
        assertEquals(1,report.noSlot)
        assertEquals(1,report.tooLarge)
        assertEquals(1,report.unsupportedFormat)
        assertEquals(1,report.unsafePath)
        assertEquals(0,report.accepted)
        assertTrue(handoff.choices().isEmpty())
        assertFalse(report.toString().contains("sample.mp3"))
        assertFalse(report.toString().contains("somepeer"))
    }

    @Test fun boundedLargeHiResFileRemainsAvailableForManualReview() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"ouruser",81,true)
        val large=A0SoulseekSearchFile("Albums\\long-hires.flac",
            96L*1024L*1024L,"flac",emptyMap())
        handoff.offerParsed(A0SoulseekSearchResponse("somepeer",81,
            listOf(large),emptyList(),true,1000,0),"somepeer")
        assertEquals(1,handoff.choices().size)
        assertEquals(0,handoff.screening().tooLarge)
    }


    @Test(timeout=12000)
    fun boundedReleaseShadowRetainsLateNewPeerAfterEarlierPeersFill512Slots() {
        val handoff=A0ObservedCandidateHandoff(java.net.Socket(),"local",81,true,
            searchPhrase="Artist - Song",collectReleaseShadow=true)
        repeat(8) { group ->
            val peer="group-$group"
            val files=(1..64).map { n ->
                A0SoulseekSearchFile(
                    "Music\\Artist\\Album\\Artist - Noise ${group*64+n}.mp3",
                    6_400_000L,"mp3",mapOf(0 to 320,1 to 161))
            }
            handoff.offerParsed(A0SoulseekSearchResponse(
                peer,81,files,emptyList(),true,128_000,0),peer)
        }
        val before=handoff.releaseShadowChoices()
        assertEquals(512,before.size)
        assertFalse(before.any { it.peerUsername=="late" })
        handoff.offerParsed(A0SoulseekSearchResponse("late",81,listOf(
            A0SoulseekSearchFile("Music\\Artist\\Album\\Artist - Song.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 161))),
            emptyList(),true,128_000,0),"late")
        val after=handoff.releaseShadowChoices()
        assertEquals(512,after.size)
        assertTrue(after.any { it.peerUsername=="late" })
        assertTrue(after.groupBy { it.peerUsername }.values.all { it.size<=64 })
        assertEquals(1,after.count { it.peerUsername=="late" })
        handoff.cancel()
        assertTrue(handoff.releaseShadowChoices().isEmpty())
    }

    @Test fun onlyValidatedParsedPeerRepliesMayBeCreditedAsSuccessful() {
        val handoff=A0ObservedCandidateHandoff(java.net.Socket(),"local",81,true)
        assertFalse(handoff.offerSearchResult(byteArrayOf(1,2,3),"claimed"))
        assertEquals(0,handoff.screening().responses)
        val valid=A0SoulseekSearchResponse("claimed",81,
            listOf(A0SoulseekSearchFile("Music\\Artist - Song.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 161))),
            emptyList(),true,128_000,0)
        assertFalse(handoff.offerParsed(valid,"other-peer"))
        assertEquals(0,handoff.screening().responses)
        assertTrue(handoff.offerParsed(valid,"claimed"))
        assertEquals(1,handoff.screening().responses)
        handoff.cancel()
        assertFalse(handoff.offerParsed(valid,"claimed"))
    }
}
