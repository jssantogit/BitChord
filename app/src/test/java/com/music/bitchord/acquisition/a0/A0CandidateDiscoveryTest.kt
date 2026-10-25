package com.music.bitchord.acquisition.a0

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Issue #177: discovery is ranked across peers, not first-reply-wins. */
class A0CandidateDiscoveryTest {
    private fun file(name: String, bitrate: Int = 320) =
        A0SoulseekSearchFile("Music\\$name", 5L * 1024 * 1024, "mp3", mapOf(0 to bitrate))

    private fun reply(peer: String, files: List<A0SoulseekSearchFile>) =
        A0SoulseekSearchResponse(peer, 177, files, emptyList(), true, 1000, 0)

    @Test fun laterRelevantPeerOutranksEarlierUnrelatedPeer() {
        val handoff = A0ObservedCandidateHandoff(
            Socket(), "local", 177, true, searchPhrase = "ILLIT - Magnetic"
        )
        handoff.offerParsed(reply("first", listOf(file("other-song.mp3"))), "first")
        handoff.offerParsed(reply("second", listOf(file("ILLIT - Magnetic.mp3"))), "second")
        assertEquals(2, handoff.screening().responses)
        assertEquals("ILLIT - Magnetic.mp3", handoff.choices().first().displayName)
        assertTrue(handoff.choices().first().matchScore > handoff.choices().last().matchScore)
        assertFalse(handoff.screening().toString().contains("ILLIT"))
    }

    @Test fun boundedCollectorLetsLateGoodResultReplaceWorstAndCountsDuplicates() {
        val handoff = A0ObservedCandidateHandoff(
            Socket(), "local", 177, true, searchPhrase = "ILLIT Magnetic"
        )
        repeat(60) { i ->
            handoff.offerParsed(reply("peer$i", listOf(file("unrelated-$i.mp3"))), "peer$i")
        }
        handoff.offerParsed(reply("best", listOf(file("ILLIT Magnetic.mp3"))), "best")
        handoff.offerParsed(reply("best", listOf(file("ILLIT Magnetic.mp3"))), "best")
        val report = handoff.screening()
        assertEquals(48, report.accepted)
        assertTrue(report.capacityDropped > 0)
        assertEquals(1, report.duplicates)
        assertEquals("ILLIT Magnetic.mp3", handoff.choices().first().displayName)
    }

    @Test fun aLackOfSlotIsDiagnosedRatherThanPresentedAsSelectable() {
        val handoff = A0ObservedCandidateHandoff(Socket(), "local", 177, true)
        val noSlot = A0SoulseekSearchResponse(
            "peer", 177, listOf(file("ILLIT Magnetic.mp3")),
            emptyList(), false, 1000, 2
        )
        handoff.offerParsed(noSlot, "peer")
        assertEquals(1, handoff.screening().noSlot)
        assertEquals(0, handoff.screening().accepted)
        assertTrue(handoff.choices().isEmpty())
    }

    @Test fun queryVariantsAreExplicitBoundedAndUnicodeSafe() {
        assertEquals(listOf("ILLIT Magnetic", "Magnetic"),
            A0SearchQuery.variants("ILLIT — Magnetic"))
        assertEquals(listOf("Björk Jóga", "Bjork Joga", "Jóga"),
            A0SearchQuery.variants("Björk - Jóga"))
        assertEquals(listOf("magnetic"), A0SearchQuery.variants("magnetic"))
        assertTrue(A0SearchQuery.variants("artist - title").size <= 3)
    }
    @Test fun rankingIsDeterministicAcrossDifferentPeerArrivalOrder() {
        val one = A0ObservedCandidateHandoff(
            Socket(), "local", 177, true, searchPhrase = "ILLIT Magnetic"
        )
        val two = A0ObservedCandidateHandoff(
            Socket(), "local", 177, true, searchPhrase = "ILLIT Magnetic"
        )
        val replies = listOf(
            reply("c", listOf(file("B-sides.mp3"))),
            reply("b", listOf(file("ILLIT Magnetic.mp3", 320))),
            reply("a", listOf(file("ILLIT Magnetic live.mp3", 128))),
        )
        replies.forEach { one.offerParsed(it, it.peerUsername) }
        replies.reversed().forEach { two.offerParsed(it, it.peerUsername) }
        assertEquals(one.choices().map { it.displayName }, two.choices().map { it.displayName })
        assertEquals("ILLIT Magnetic.mp3", one.choices().first().displayName)
    }

    @Test fun saveRouteDoesNotLetUnsupportedFormatsConsumeCandidateBudget() {
        val handoff = A0ObservedCandidateHandoff(
            Socket(), "local", 177, true, searchPhrase = "ILLIT Magnetic",
            saveFormatsOnly = true
        )
        handoff.offerParsed(reply("peer", listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.ogg", 100000, "ogg", emptyMap()),
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.m4a", 100000, "m4a", emptyMap()),
            file("ILLIT Magnetic.mp3")
        )), "peer")
        assertEquals(1, handoff.choices().size)
        assertEquals(2, handoff.screening().unsupportedFormat)
        assertEquals(1, handoff.screening().accepted)
    }

    @Test fun cancellationRejectsLateSearchRepliesWithoutReopeningCandidates() {
        val handoff=A0ObservedCandidateHandoff(Socket(), "local", 177, true)
        handoff.cancel()
        handoff.offerParsed(reply("peer", listOf(file("ILLIT Magnetic.mp3"))), "peer")
        assertTrue(handoff.choices().isEmpty())
        assertEquals(0,handoff.screening().responses)
    }
    @Test fun longRemoteFilenameKeepsExtensionForManualSave() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT Magnetic",saveFormatsOnly=true)
        val longName="ILLIT Magnetic " + "very-long-release-name-".repeat(6) + ".mp3"
        handoff.offerParsed(reply("peer",listOf(file(longName))),"peer")
        val choice=handoff.choices().single()
        assertTrue(choice.displayName.endsWith(".mp3"))
        assertNotNull(A5ManualSaveGate.safeDestinationName(choice.displayName,choice.sizeBytes))
    }


    @Test fun onePeerDoesNotMonopolizeTheSelectionAcrossSearchReplies() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT Magnetic")
        handoff.offerParsed(reply("peer-a",(1..35).map { file("ILLIT Magnetic v$it.mp3") }),"peer-a")
        handoff.offerParsed(reply("peer-b",listOf(file("ILLIT Magnetic.mp3"))),"peer-b")
        assertEquals(11,handoff.choices().size)
        assertEquals(25,handoff.screening().perPeerDropped)
        assertTrue(handoff.choices().any { it.displayName=="ILLIT Magnetic.mp3" })
    }

    @Test fun equalFilenameOfferedByDifferentPeersRemainsSeparateManualChoices() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true)
        handoff.offerParsed(reply("peer-a",listOf(file("ILLIT Magnetic.mp3"))),"peer-a")
        handoff.offerParsed(reply("peer-b",listOf(file("ILLIT Magnetic.mp3"))),"peer-b")
        assertEquals(2,handoff.choices().size)
    }

    @Test fun sanitizedScreeningDistinguishesRelevantAndDubiousCandidates() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        handoff.offerParsed(reply("peer",listOf(
            A0SoulseekSearchFile("Music\\ILLIT - Magnetic.mp3",6_400_000,"mp3",
                mapOf(0 to 320,1 to 161)),
            A0SoulseekSearchFile("Music\\Something Else.mp3",100_000,"mp3",
                mapOf(0 to 96,1 to 400))
        )),"peer")
        val report=handoff.screening()
        assertEquals(2,report.accepted)
        assertEquals(1,report.strongMatches)
        assertEquals(1,report.weakMatches)
        assertEquals(1,report.lowQuality)
        assertFalse(report.toString().contains("ILLIT"))
    }
}
