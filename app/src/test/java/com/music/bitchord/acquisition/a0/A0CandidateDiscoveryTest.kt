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
}
