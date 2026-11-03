package com.music.bitchord.acquisition.a0

import java.net.Socket
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Manual choice: no static CC0 filename, no unrequested transfers. */
class A0ObservedCandidateHandoffTest {
    @Test fun automaticShadowModeDoesNotMaintainTheUnneededManualCandidateRanking() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",81,true,
            searchPhrase="ILLIT - More",requestedAlbum="BREAK EVEN",
            collectReleaseShadow=true,collectManualCandidates=false,
            releaseQueries=listOf("ILLIT - More","ILLIT - Pop"))
        val files=(0 until 240).map { n ->
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\BREAK EVEN\\ILLIT - More $n.flac",
                38_000_000L,"flac",mapOf(1 to 173,4 to 44_100,5 to 16))
        }
        val unrelated=(0 until 240).map { n ->
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\Other Album\\Unrelated Song $n.flac",
                38_000_000L,"flac",mapOf(1 to 173,4 to 44_100,5 to 16))
        }
        assertTrue(handoff.offerParsed(A0SoulseekSearchResponse(
            "peer",81,unrelated+files,emptyList(),true,128_000,0),"peer"))
        assertEquals(240,handoff.screening().validBeforeLimit)
        assertTrue(handoff.choices().isEmpty())
        assertEquals(64,handoff.releaseShadowChoices().size)
        assertTrue(handoff.releaseShadowSnapshot().shadowDroppedAtPeerLimit>0)
    }


    @Test fun perTicketFunnelDistinguishesIrrelevantDuplicateAndNewCandidates() {
        val tickets=A0SearchTicketRegistry(81)
        val collector=A0ObservedCandidateHandoff(Socket(),"local",81,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            collectReleaseShadow=true,collectManualCandidates=false,
            releaseQueries=listOf("ILLIT - Magnetic"),
            acceptedSearchTokens=tickets.accepted)
        val relevant=A0SoulseekSearchFile(
            "Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Magnetic.mp3",
            6_440_000L,"mp3",mapOf(0 to 320,1 to 161))
        val unrelated=A0SoulseekSearchFile(
            "Shared\\ILLIT\\SUPER REAL ME\\Another Song.mp3",
            6_440_000L,"mp3",mapOf(0 to 320,1 to 161))
        fun response(peer:String,token:Int,files:List<A0SoulseekSearchFile>) =
            A0SoulseekSearchResponse(peer,token,files,emptyList(),
                true,128_000,0)
        assertTrue(collector.offerParsed(
            response("source",81,listOf(unrelated,relevant)),"source"))
        assertTrue(tickets.register(82))
        // A second search returning a known claim is not new discovery.
        assertTrue(collector.offerParsed(
            response("source",82,listOf(relevant)),"source"))
        // A different peer claiming the same recording is a new offer.
        assertTrue(collector.offerParsed(
            response("other",82,listOf(relevant)),"other"))
        assertFalse(collector.offerParsed(
            response("other",83,listOf(relevant)),"other"))
        assertEquals(81,tickets.tokenAt(0))
        assertEquals(82,tickets.tokenAt(1))
        assertNull(tickets.tokenAt(2))
        assertEquals(A0ObservedCandidateHandoff.TicketScreening(
            titleMatched=1,uniqueAdmitted=1,shadowInserted=1),
            collector.ticketScreening(81))
        assertEquals(A0ObservedCandidateHandoff.TicketScreening(
            titleMatched=2,uniqueAdmitted=1,shadowInserted=1),
            collector.ticketScreening(82))
        assertEquals(A0ObservedCandidateHandoff.TicketScreening(),
            collector.ticketScreening(83))
        assertEquals(1,collector.screening().shadowTitleFiltered)
        assertEquals(2,collector.releaseShadowChoices().size)
        assertFalse(collector.ticketScreening(81).toString().contains("source"))
        assertFalse(collector.ticketScreening(82).toString().contains("Magnetic"))
    }

    @Test fun ticketFunnelCountsOnlyFilesPassingPreTitleSafetyChecks() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",81,true,
            searchPhrase="ILLIT - Magnetic",collectReleaseShadow=true,
            collectManualCandidates=false,
            releaseQueries=listOf("ILLIT - Magnetic"))
        val proper=A0SoulseekSearchFile("Shared\\ILLIT\\Magnetic.mp3",
            6_440_000L,"mp3",mapOf(0 to 320,1 to 161))
        val badPath=A0SoulseekSearchFile("../Magnetic.mp3",
            6_440_000L,"mp3",emptyMap())
        val unsupported=A0SoulseekSearchFile("Shared\\Magnetic.exe",
            6_440_000L,"exe",emptyMap())
        assertTrue(collector.offerParsed(A0SoulseekSearchResponse(
            "source",81,listOf(proper,badPath,unsupported),emptyList(),
            true,128_000,0),"source"))
        assertEquals(A0ObservedCandidateHandoff.TicketScreening(
            titleMatched=1,uniqueAdmitted=1,shadowInserted=1),
            collector.ticketScreening(81))
        assertEquals(3,collector.screening().publicFiles)
    }

    @Test fun automaticShadowReportsTitlePrefilterRejectsWithoutRetainingIdentity() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",81,true,
            searchPhrase="ILLIT - Magnetic",collectReleaseShadow=true,
            collectManualCandidates=false,
            releaseQueries=listOf("ILLIT - Magnetic","ILLIT - Lucky Girl Syndrome"))
        val files=listOf(
            A0SoulseekSearchFile("Shared\\ILLIT\\Album\\Other Song.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 161)),
            A0SoulseekSearchFile("Shared\\ILLIT\\Album\\Magnetic.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 161))
        )
        assertTrue(handoff.offerParsed(A0SoulseekSearchResponse(
            "source",81,files,emptyList(),true,128_000,0),"source"))
        assertEquals(1,handoff.releaseShadowChoices().size)
        assertEquals(1,handoff.screening().shadowTitleFiltered)
        assertEquals(1,handoff.screening().validBeforeLimit)
        assertTrue(handoff.choices().isEmpty())
        assertFalse(handoff.screening().toString().contains("Other Song"))
        assertFalse(handoff.screening().toString().contains("source"))
    }

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
        val afterSnapshot=handoff.releaseShadowSnapshot()
        val after=afterSnapshot.choices
        assertEquals(512,after.size)
        assertEquals(1,afterSnapshot.shadowEvicted)
        assertEquals(0,afterSnapshot.shadowDroppedAtGlobalLimit)
        assertEquals(0,afterSnapshot.shadowDroppedAtPeerLimit)
        assertTrue(after.any { it.peerUsername=="late" })
        assertTrue(after.groupBy { it.peerUsername }.values.all { it.size<=64 })
        assertEquals(1,after.count { it.peerUsername=="late" })
        handoff.cancel()
        assertTrue(handoff.releaseShadowChoices().isEmpty())
    }

    @Test fun ticketOfUnsentVariationMustNeverCountAsValidReply() {
        val tickets=A0SearchTicketRegistry(81)
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",81,true,
            acceptedSearchTokens=tickets.accepted)
        fun reply(token:Int)=A0SoulseekSearchResponse("peer",token,
            listOf(A0SoulseekSearchFile("Music\\\\Boy In Luv.flac",
                30000000L,"flac",mapOf(1 to 235))),emptyList(),true,100000,0)
        assertFalse(handoff.offerParsed(reply(82),"peer"))
        assertTrue(tickets.register(82))
        assertTrue(handoff.offerParsed(reply(82),"peer"))
        assertFalse(handoff.offerParsed(reply(83),"peer"))
        assertEquals(1,handoff.screening().responses)
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

    @Test fun cheapProgressCountersTrackOffersWithoutClaimClustering() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",81,true,
            searchPhrase="ILLIT - More")
        assertEquals(A0ObservedCandidateHandoff.Progress(0,0,0),handoff.progress())
        val valid=A0SoulseekSearchResponse("source",81,listOf(
            A0SoulseekSearchFile("Music\\ILLIT - More.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 180)),
            A0SoulseekSearchFile("Music\\other.mp3",
                6_400_000L,"mp3",emptyMap())),
            emptyList(),true,80_000,0)
        assertTrue(handoff.offerParsed(valid,"source"))
        assertEquals(A0ObservedCandidateHandoff.Progress(1,2,2),handoff.progress())
        assertTrue(handoff.offerParsed(valid,"source"))
        assertEquals(A0ObservedCandidateHandoff.Progress(2,4,2),handoff.progress())
        assertEquals(2,handoff.screening().accepted)
        handoff.cancel()
        assertEquals(A0ObservedCandidateHandoff.Progress(2,4,0),handoff.progress())
    }
}
