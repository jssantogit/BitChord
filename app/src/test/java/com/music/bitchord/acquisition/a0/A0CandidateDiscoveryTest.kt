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

    @Test fun cautiousPreflightPromotesRelevantStudioAndCountsRiskWithoutDiscardingManualChoices() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        handoff.offerParsed(reply("live-peer",listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic live.mp3",6_400_000,"mp3",
                mapOf(0 to 320,1 to 161))
        )),"live-peer")
        handoff.offerParsed(reply("studio-peer",listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",6_400_000,"mp3",
                mapOf(0 to 320,1 to 161))
        )),"studio-peer")
        val sorted=handoff.choices()
        assertEquals(2,sorted.size)
        assertEquals("ILLIT Magnetic.mp3",sorted.first().displayName)
        assertEquals(A0CandidatePreflight.Confidence.STRONG,sorted.first().preflight.confidence)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,sorted.last().preflight.confidence)
        assertEquals(1,handoff.screening().preflightStrong)
        assertEquals(1,handoff.screening().preflightUnsafe)
        assertEquals(0,handoff.screening().preflightReview)
    }

    @Test fun unknownExpectedDurationDoesNotMakeAnyPeerAutomaticallyReady() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic")
        handoff.offerParsed(reply("p",listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",6_400_000,"mp3",
                mapOf(0 to 320,1 to 161))
        )),"p")
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,
            handoff.choices().single().preflight.confidence)
        assertEquals(0,handoff.screening().preflightStrong)
    }

    @Test fun conflictingArtistRemainsSelectableManuallyButCannotLeadStrongCandidate() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val misplaced=A0SoulseekSearchFile(
            "Music\\ILLIT\\Other Artist - Magnetic.mp3",6_400_000L,"mp3",
            mapOf(0 to 320,1 to 161))
        val official=A0SoulseekSearchFile(
            "Music\\ILLIT\\ILLIT - Magnetic.mp3",6_400_000L,"mp3",
            mapOf(0 to 320,1 to 161))
        handoff.offerParsed(reply("a",listOf(misplaced)),"a")
        handoff.offerParsed(reply("b",listOf(official)),"b")
        assertEquals(2,handoff.choices().size)
        assertEquals(A0CandidatePreflight.Confidence.STRONG,
            handoff.choices()[0].preflight.confidence)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,
            handoff.choices()[1].preflight.confidence)
    }

    @Test fun sameRecordingDifferentPeersPrefersReadySourceWithoutChangingIdentity() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val same=A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",
            6_400_000L,"mp3",mapOf(0 to 320,1 to 161))
        collector.offerParsed(A0SoulseekSearchResponse("slow",177,listOf(same),
            emptyList(),true,4_096,40),"slow")
        collector.offerParsed(A0SoulseekSearchResponse("fast",177,listOf(same),
            emptyList(),true,256_000,0),"fast")
        val choices=collector.choices()
        assertEquals(2,choices.size)
        assertEquals(A0CandidatePreflight.Confidence.STRONG,
            choices[0].preflight.confidence)
        assertEquals(choices[0].preflight,choices[1].preflight)
        assertEquals(choices[0].matchScore,choices[1].matchScore)
        assertEquals(A0PeerAvailability.Tier.READY,choices[0].peerAvailability.tier)
        assertEquals(A0PeerAvailability.Tier.CONGESTED,choices[1].peerAvailability.tier)
        assertEquals(1,collector.screening().peersReady)
        assertEquals(1,collector.screening().peersCongested)
        assertEquals(1,collector.screening().simulatedSuggested)
        assertEquals(0,collector.screening().simulatedAmbiguous)
        assertEquals(1,collector.screening().claimsDistinct)
        assertEquals(1,collector.screening().claimsRepeated)
        assertFalse(collector.screening().toString().contains("slow"))
    }

    @Test fun fasterPeerNeverOutranksARecordingWithStrongerMusicalConfidence() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        collector.offerParsed(A0SoulseekSearchResponse("fast",177,listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic live.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 161))),
            emptyList(),true,256_000,0),"fast")
        collector.offerParsed(A0SoulseekSearchResponse("slow",177,listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",
                6_400_000L,"mp3",mapOf(0 to 320,1 to 161))),
            emptyList(),true,4_096,40),"slow")
        val choices=collector.choices()
        assertEquals(2,choices.size)
        assertEquals(A0CandidatePreflight.Confidence.STRONG,
            choices[0].preflight.confidence)
        assertEquals(A0PeerAvailability.Tier.CONGESTED,
            choices[0].peerAvailability.tier)
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,
            choices[1].preflight.confidence)
        assertEquals(1,collector.screening().simulatedSuggested)
        assertEquals(0,collector.screening().simulatedAmbiguous)
    }

    @Test fun impossibleAdvertisedBitrateOutsideParserSoftRangeIsNotSilentlyUnknown() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        collector.offerParsed(reply("peer",listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",6_400_000L,"mp3",
                mapOf(0 to 2_000_000,1 to 161))
        )),"peer")
        val observed=collector.choices().single()
        assertEquals(A0CandidatePreflight.Confidence.UNSAFE,observed.preflight.confidence)
        assertTrue(A0CandidatePreflight.Reason.ENCODING_CONFLICT in observed.preflight.reasons)
    }

    @Test fun differentDeclaredSizeRemainsDistinctAndShadowAbstains() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        for((i,size) in listOf(6_400_000L,6_400_001L).withIndex())
            collector.offerParsed(reply("peer$i",listOf(
                A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",size,"mp3",
                    mapOf(0 to 320,1 to 161))
            )),"peer$i")
        assertEquals(2,collector.screening().claimsDistinct)
        assertEquals(0,collector.screening().claimsRepeated)
        assertEquals(1,collector.screening().simulatedAmbiguous)
    }

    @Test fun apparentCopiesFromManyPeersCannotExhaustCandidateList() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val same=A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",6_400_000L,
            "mp3",mapOf(0 to 320,1 to 161))
        repeat(18) { i ->
            val peer="peer"+i.toString().padStart(2,'0')
            collector.offerParsed(reply(peer,listOf(same)),peer)
        }
        collector.offerParsed(reply("different",listOf(
            A0SoulseekSearchFile("Music\\ILLIT Magnetic.flac",24_000_000L,
                "flac",mapOf(1 to 161,4 to 44100,5 to 16))
        )),"different")
        assertEquals(7,collector.choices().size)
        assertEquals(6,collector.choices().count { it.displayName.endsWith(".mp3") })
        assertEquals(1,collector.choices().count { it.displayName.endsWith(".flac") })
        assertEquals(12,collector.screening().claimBudgetDropped)
        assertEquals(2,collector.screening().claimsDistinct)
        assertEquals(5,collector.screening().claimsRepeated)
    }

    @Test fun identicalFilenameFromConflictingFoldersStaysSeparateInShadowEvidence() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val attrs=mapOf(0 to 320,1 to 161)
        collector.offerParsed(reply("a",listOf(
            A0SoulseekSearchFile("Music\\ILLIT\\Magnetic.mp3",6_400_000L,"mp3",attrs))),"a")
        collector.offerParsed(reply("b",listOf(
            A0SoulseekSearchFile("Music\\Other\\Magnetic.mp3",6_400_000L,"mp3",attrs))),"b")
        assertEquals(2,collector.choices().size)
        assertEquals(2,collector.screening().claimsDistinct)
        assertEquals(0,collector.screening().claimsRepeated)
        assertEquals(1,collector.screening().simulatedSuggested)
    }


    @Test fun peerAvailabilityMustNotChooseBetweenDifferentStrongClaims() {
        // Both files score identically and have the same declared bitrate.
        // They are still distinct metadata declarations. Source congestion
        // must not decide which musical candidate leads the manual list.
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val attrs=mapOf(0 to 320,1 to 161)
        val first=A0SoulseekSearchFile(
            "Music\\ILLIT - Magnetic.mp3",6_400_000L,"mp3",attrs)
        val second=A0SoulseekSearchFile(
            "Music\\ILLIT Magnetic.mp3",6_400_000L,"mp3",attrs)
        collector.offerParsed(A0SoulseekSearchResponse("slow",177,listOf(first),
            emptyList(),true,4_096,40),"slow")
        collector.offerParsed(A0SoulseekSearchResponse("fast",177,listOf(second),
            emptyList(),true,256_000,0),"fast")
        val choices=collector.choices()
        assertEquals(2,choices.size)
        assertEquals(choices[0].matchScore,choices[1].matchScore)
        assertEquals(choices[0].preflight,choices[1].preflight)
        assertEquals(choices[0].declaredBitrateKbps,choices[1].declaredBitrateKbps)
        assertEquals(2,collector.screening().claimsDistinct)
        assertEquals(1,collector.screening().simulatedAmbiguous)
        assertEquals("ILLIT - Magnetic.mp3",choices.first().displayName)
        assertEquals(A0PeerAvailability.Tier.CONGESTED,choices.first().peerAvailability.tier)
    }

    @Test fun sourceQueueOnlyBreaksTiesForExactlyEquivalentDeclarations() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val same=A0SoulseekSearchFile("Music\\ILLIT Magnetic.mp3",
            6_400_000L,"mp3",mapOf(0 to 320,1 to 161))
        collector.offerParsed(A0SoulseekSearchResponse("peer-a",177,listOf(same),
            emptyList(),true,850_000,2),"peer-a")
        collector.offerParsed(A0SoulseekSearchResponse("peer-z",177,listOf(same),
            emptyList(),true,80_000,0),"peer-z")
        val options=collector.choices()
        assertEquals(2,options.size)
        assertEquals("peer-z",options.first().peerUsername)
        assertEquals(1,collector.screening().claimsDistinct)
        assertEquals(1,collector.screening().simulatedSuggested)
        assertEquals(0,collector.claimPreview().preferredIndex)
    }

    @Test fun misleadingUnicodeCandidateStaysManualButCannotBecomeStrong() {
        val collector=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",expectedDurationSeconds=161)
        val name="ILLIT Magnetic\u202E.mp3"
        collector.offerParsed(reply("peer",listOf(
            A0SoulseekSearchFile("Music/ILLIT/"+name,6_400_000L,"mp3",
                mapOf(0 to 320,1 to 161))
        )),"peer")
        assertEquals(1,collector.choices().size)
        assertEquals(A0CandidatePreflight.Confidence.REVIEW,
            collector.choices().single().preflight.confidence)
        assertEquals(1,collector.screening().preflightReview)
        assertEquals(0,collector.screening().simulatedSuggested)
        assertEquals(1,collector.screening().simulatedNoStrong)
    }
}
