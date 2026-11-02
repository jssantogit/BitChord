package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQualityProfile
import com.music.bitchord.download.SoulseekBatchPlan
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0AutoDiscoveryCoordinatorTest {
    private val quality=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val entries=listOf(
        SoulseekBatchPlan.Entry("one","ILLIT - My World","SUPER REAL ME",120),
        SoulseekBatchPlan.Entry("two","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("three","ILLIT - Midnight Fiction","SUPER REAL ME",153),
        SoulseekBatchPlan.Entry("four","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",160),
    )
    private fun claims(tracks:List<SoulseekBatchPlan.Entry>):List<A0ObservedCandidateHandoff.Choice> {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=entries[0].query,requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=120,qualityProfile=quality,collectReleaseShadow=true)
        val files=tracks.map {
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\${it.query}.mp3",
                it.durationSeconds!!.toLong()*40_000L,"mp3",
                mapOf(0 to 320,1 to it.durationSeconds))
        }
        handoff.offerParsed(A0SoulseekSearchResponse(
            "ready-peer",177,files,emptyList(),true,128_000,0),"ready-peer")
        return handoff.releaseShadowChoices()
    }
    private class FakeSearch(
        private val sources:(SoulseekBatchPlan.Entry)->A0AutoDiscoveryCoordinator.SearchResult,
    ):A0AutoDiscoveryCoordinator.SearchPort {
        val requests=mutableListOf<String>()
        var closed=0
        override fun search(
            entry:SoulseekBatchPlan.Entry,
            releaseSlots:List<SoulseekBatchPlan.Entry>,
            isCancelled:()->Boolean,
        ):A0AutoDiscoveryCoordinator.SearchResult {
            requests.add(entry.trackId)
            return sources(entry)
        }
        override fun close() {closed++}
    }
    private fun batch(done:MutableList<String>)=A0AutoBatchExecutor { entry ->
        object:A0AutoAcquisitionExecutor.Port {
            override fun fetch(candidate:A0ObservedCandidateHandoff.Choice,
                isCancelled:()->Boolean):A0AutoAcquisitionExecutor.FetchResult {
                val file=File.createTempFile("a0-discovery-test-", ".observed")
                RandomAccessFile(file,"rw").use {
                    it.setLength(entry.durationSeconds!!.toLong()*40_000L)
                }
                return A0AutoAcquisitionExecutor.FetchResult.Received(
                    A0VerifiedByteStager.Receipt(file,file.length(),
                        "a".repeat(64),"ID3_HEADER_ONLY"))
            }
            override fun inspect(receipt:A0VerifiedByteStager.Receipt,
                expectedExtension:String,isCancelled:()->Boolean)=
                A0AutoPostTransferTechnicalGate.Decoded(
                    A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",
                    entry.durationSeconds!!.toLong()*1000L,320,44100,null,true)
            override fun publish(receipt:A0VerifiedByteStager.Receipt,
                safeName:String,isCancelled:()->Boolean)
                :A0AutoAcquisitionExecutor.PublishResult {
                done.add(entry.trackId)
                return A0AutoAcquisitionExecutor.PublishResult.DURABLE
            }
            override fun cleanup(receipt:A0VerifiedByteStager.Receipt) {
                assertTrue(receipt.file.delete())
            }
        }
    }

    @Test fun wrappingReservationReportKeepsRealFolderQueryCount() {
        val report=A0AutoDiscoveryCoordinator.Report(
            2,A0AutoBatchExecutor.Report(listOf(
                A0AutoBatchExecutor.TrackStatus.PUBLISHED),1),
            folderQueries=1)
        val wrapped=report.withReservationReleased(true)
        assertEquals(2,wrapped.searches)
        assertEquals(1,wrapped.folderQueries)
        assertEquals(1,wrapped.batch.published)
        assertTrue(wrapped.unusedReservationReleased)
        assertEquals(0,report.folderQueries-1)
    }

    @Test fun successfulPublishedFolderBrowseAvoidsRemainingThreePublicSearches() {
        val search=object:A0AutoDiscoveryCoordinator.SearchPort {
            var searches=0
            var folderReads=0
            override fun search(
                entry:SoulseekBatchPlan.Entry,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean,
            ):A0AutoDiscoveryCoordinator.SearchResult {
                searches++
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(entry)))
            }
            override fun inspectPublishedFolder(
                source:A0ObservedCandidateHandoff.Choice,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean,
            ):A0AutoDiscoveryCoordinator.SearchResult {
                folderReads++
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(entries.drop(1)))
            }
            override fun close() {}
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(1,search.searches)
        assertEquals(1,search.folderReads)
        assertEquals(1,result.folderQueries)
        assertEquals(4,result.batch.published)
        assertEquals(entries.map {it.trackId},done)
    }

    @Test fun unavailablePeerFoldersNeverCauseUnboundedBrowsingOrBlockPublicSearch() {
        val search=object:A0AutoDiscoveryCoordinator.SearchPort {
            var searched=0
            var inspected=0
            override fun search(entry:SoulseekBatchPlan.Entry,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean):A0AutoDiscoveryCoordinator.SearchResult {
                searched++
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(entry)))
            }
            override fun inspectPublishedFolder(
                source:A0ObservedCandidateHandoff.Choice,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean,
            ):A0AutoDiscoveryCoordinator.SearchResult {
                inspected++
                return A0AutoDiscoveryCoordinator.SearchResult.NoReply
            }
            override fun close() {}
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(4,result.batch.published)
        assertEquals(4,search.searched)
        // All four simulated downloads use the SAME peer + folder:
        // the failed browse is attempted once, not twice.
        assertEquals(1,search.inspected)
        assertEquals(1,result.folderQueries)
    }

    @Test fun distinctPublishedFoldersRespectShortAlbumProbeBudgetOfTwo() {
        val search=object:A0AutoDiscoveryCoordinator.SearchPort {
            var searched=0
            var inspected=0
            override fun search(entry:SoulseekBatchPlan.Entry,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean):A0AutoDiscoveryCoordinator.SearchResult {
                searched++
                val observation=A0ObservedCandidateHandoff(
                    Socket(),"local",177,true,
                    searchPhrase=entry.query,requestedAlbum=entry.album,
                    expectedDurationSeconds=entry.durationSeconds,
                    qualityProfile=quality,collectReleaseShadow=true
                )
                val filename="Shared\\Unique-${entry.trackId}\\SUPER REAL ME\\${entry.query}.mp3"
                observation.offerParsed(A0SoulseekSearchResponse(
                    "ready-peer",177,
                    listOf(A0SoulseekSearchFile(
                        filename,entry.durationSeconds!!.toLong()*40_000L,
                        "mp3",mapOf(0 to 320,1 to entry.durationSeconds))),
                    emptyList(),true,128_000,0),"ready-peer")
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    observation.releaseShadowChoices())
            }
            override fun inspectPublishedFolder(
                source:A0ObservedCandidateHandoff.Choice,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean):A0AutoDiscoveryCoordinator.SearchResult {
                inspected++
                return A0AutoDiscoveryCoordinator.SearchResult.NoReply
            }
            override fun close() {}
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(4,result.batch.published)
        assertEquals(4,search.searched)
        // Distinct real source directories exercise the budget, not dedup.
        assertEquals(2,search.inspected)
        assertEquals(2,result.folderQueries)
    }

    @Test fun folderBrowseClaimFromAnotherPeerCannotAvoidPublicSearch() {
        val two=entries.take(2)
        val search=object:A0AutoDiscoveryCoordinator.SearchPort {
            var publicSearches=0
            override fun search(entry:SoulseekBatchPlan.Entry,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean):A0AutoDiscoveryCoordinator.SearchResult {
                publicSearches++
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(entry)))
            }
            override fun inspectPublishedFolder(
                source:A0ObservedCandidateHandoff.Choice,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean,
            ):A0AutoDiscoveryCoordinator.SearchResult =
                A0AutoDiscoveryCoordinator.SearchResult.Found(
                    alternatePeerClaims(listOf(two[1])))
            override fun close() {}
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(2,search.publicSearches)
        assertEquals(2,result.batch.published)
        assertEquals(listOf("one","two"),done)
    }

    @Test fun clippedFolderResponseNeverPoisonsLaterCompletePublicSearch() {
        val two=entries.take(2)
        val search=object:A0AutoDiscoveryCoordinator.SearchPort {
            var searches=0
            override fun search(entry:SoulseekBatchPlan.Entry,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean):A0AutoDiscoveryCoordinator.SearchResult {
                searches++
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(entry)))
            }
            override fun inspectPublishedFolder(
                source:A0ObservedCandidateHandoff.Choice,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean,
            ):A0AutoDiscoveryCoordinator.SearchResult =
                A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(two),setOf(1))
            override fun close() {}
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(2,search.searches)
        assertEquals(2,result.batch.published)
        assertEquals(0,result.batch.manualReview)
    }

    @Test fun unresponsiveFolderBrowseFallsBackToNormalPublicSearch() {
        val search=object:A0AutoDiscoveryCoordinator.SearchPort {
            var searches=0
            override fun search(entry:SoulseekBatchPlan.Entry,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean):A0AutoDiscoveryCoordinator.SearchResult {
                searches++
                return A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(entry)))
            }
            override fun inspectPublishedFolder(
                source:A0ObservedCandidateHandoff.Choice,
                releaseSlots:List<SoulseekBatchPlan.Entry>,
                isCancelled:()->Boolean,
            ):A0AutoDiscoveryCoordinator.SearchResult =
                A0AutoDiscoveryCoordinator.SearchResult.NoReply
            override fun close() {}
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries.take(2),quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(2,search.searches)
        assertEquals(2,result.batch.published)
        assertEquals(1,result.folderQueries)
    }

    @Test fun spentPhysicalFileMustNotSuppressSearchForDuplicateCanonicalSong() {
        // Two distinct slots may share artist/title/duration while still
        // requiring independent physical files. Reusing the SAME peer/path
        // is prohibited after its first durable publication.
        val duplicates=listOf(
            SoulseekBatchPlan.Entry("duplicate-a","ILLIT - Magnetic",
                "SUPER REAL ME",161),
            SoulseekBatchPlan.Entry("duplicate-b","ILLIT - Magnetic",
                "SUPER REAL ME",161),
        )
        val search=FakeSearch { slot ->
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                if(slot.trackId=="duplicate-a")claims(listOf(slot))
                else alternatePeerClaims(listOf(slot)))
        }
        val done=mutableListOf<String>()
        val actions=mutableListOf<A0AutoDiscoveryCoordinator.CacheAction>()
        val report=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            duplicates,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCacheEvidence={_,event -> actions.add(event.action)})
        assertEquals(listOf("duplicate-a","duplicate-b"),search.requests)
        assertEquals(2,report.searches)
        assertEquals(2,report.batch.published)
        assertEquals(0,report.batch.manualReview)
        assertEquals(listOf("duplicate-a","duplicate-b"),done)
        assertTrue(A0AutoDiscoveryCoordinator.CacheAction.SEARCH_REQUIRED in actions)
    }

    @Test fun spentPhysicalFileStillCannotBeReusedIfSecondSearchFindsNoAlternative() {
        val duplicates=listOf(
            SoulseekBatchPlan.Entry("duplicate-a","ILLIT - Magnetic",
                "SUPER REAL ME",161),
            SoulseekBatchPlan.Entry("duplicate-b","ILLIT - Magnetic",
                "SUPER REAL ME",161),
        )
        val search=FakeSearch { slot ->
            if(slot.trackId=="duplicate-a")
                A0AutoDiscoveryCoordinator.SearchResult.Found(claims(listOf(slot)))
            else A0AutoDiscoveryCoordinator.SearchResult.NoReply
        }
        val done=mutableListOf<String>()
        val report=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            duplicates,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(2,report.searches)
        assertEquals(listOf("duplicate-a","duplicate-b"),search.requests)
        assertEquals(1,report.batch.published)
        assertEquals(1,report.batch.manualReview)
        assertEquals(listOf("duplicate-a"),done)
    }

    @Test fun firstQueryCoveringAllFourAlbumSlotsAvoidsThreeRedundantSearches() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Found(claims(entries))
        }
        val done=mutableListOf<String>()
        val actions=mutableListOf<A0AutoDiscoveryCoordinator.CacheAction>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCacheEvidence={_,evidence -> actions.add(evidence.action)})
        assertEquals(listOf(
            A0AutoDiscoveryCoordinator.CacheAction.SEARCH_REQUIRED,
            A0AutoDiscoveryCoordinator.CacheAction.REUSED,
            A0AutoDiscoveryCoordinator.CacheAction.REUSED,
            A0AutoDiscoveryCoordinator.CacheAction.REUSED),actions)
        assertEquals(1,result.searches)
        assertEquals(listOf("one"),search.requests)
        assertEquals(4,result.batch.published)
        assertEquals(entries.map{it.trackId},done)
        assertEquals(1,search.closed)
    }

    private fun alternatePeerClaims(
        tracks:List<SoulseekBatchPlan.Entry>,
    ):List<A0ObservedCandidateHandoff.Choice> {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=entries[0].query,requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=120,qualityProfile=quality,
            collectReleaseShadow=true)
        val files=tracks.map {
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\${it.query}.mp3",
                it.durationSeconds!!.toLong()*40_000L+1024L,"mp3",
                mapOf(0 to 320,1 to it.durationSeconds))
        }
        handoff.offerParsed(A0SoulseekSearchResponse(
            "other-peer",177,files,emptyList(),true,128_000,0),"other-peer")
        return handoff.releaseShadowChoices()
    }

    @Test fun completeReleaseCohortResolvesByteSizeTieWithoutExtraSearch() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(entries)+alternatePeerClaims(listOf(entries[0])))
        }
        val done=mutableListOf<String>()
        val observations=mutableListOf<A0ReleaseCohesionPolicy.Reason>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCohesionEvidence={_,evidence -> observations.add(evidence.reason)})
        assertEquals(4,result.batch.published)
        assertEquals(4,result.batch.attempts)
        assertEquals(listOf("one"),search.requests)
        assertEquals(entries.map {it.trackId},done)
        assertTrue(A0ReleaseCohesionPolicy.Reason.UNIQUE_RELEASE_COHORT
            in observations)
    }

    @Test fun equallyCoherentReleasesUseFirstEligibleSourceWithoutManualSelection() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(entries)+alternatePeerClaims(entries))
        }
        val done=mutableListOf<String>()
        val tieReasons=mutableListOf<A0ReleaseCohesionPolicy.Reason>()
        val decisions=mutableListOf<A0AutoCandidateSelectionPolicy.Decision>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCandidateDecision={_,decision -> decisions.add(decision)},
            onCohesionEvidence={_,evidence -> tieReasons.add(evidence.reason)})
        assertEquals(4,result.batch.attempts)
        assertEquals(4,result.batch.published)
        assertEquals(0,result.batch.manualReview)
        assertEquals(1,result.searches)
        assertEquals(entries.map { it.trackId },done)
        assertTrue(A0ReleaseCohesionPolicy.Reason.TIED_COHORTS in tieReasons)
        assertTrue(decisions.all {
            it.tieResolution==A0AutoCandidateSelectionPolicy.TieResolution.FIRST_READY
        })
    }

    @Test fun missingTracksCauseOnlyBoundedUnsearchedSlotQueries() {
        val search=FakeSearch { target ->
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(entries.filter {it.trackId==target.trackId}))
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(4,result.searches)
        assertEquals(entries.map{it.trackId},search.requests)
        assertEquals(4,result.batch.published)
    }

    @Test fun albumTransfersFirstStrongTrackBeforeSearchingSecondTrack() {
        val events=mutableListOf<String>()
        val search=FakeSearch { target ->
            events.add("search:${target.trackId}")
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(entries.filter { it.trackId==target.trackId }))
        }
        val originalBatch=batch(events)
        val report=A0AutoDiscoveryCoordinator(search,originalBatch).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(4,report.batch.published)
        assertEquals(listOf(
            "search:one","one","search:two","two",
            "search:three","three","search:four","four"),events)
    }

    @Test fun laterSiblingEvidenceMayRescueAnEarlierReviewWithoutExtraSearch() {
        val two=entries.take(2)
        val search=FakeSearch { slot ->
            if(slot.trackId=="one")A0AutoDiscoveryCoordinator.SearchResult.NoReply
            else A0AutoDiscoveryCoordinator.SearchResult.Found(claims(two))
        }
        val done=mutableListOf<String>()
        val report=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(2,report.searches)
        assertEquals(listOf("one","two"),search.requests)
        assertEquals(listOf("two","one"),done)
        assertEquals(2,report.batch.published)
    }

    @Test fun lateEvidenceCannotRepeatAlreadyTransferredOrPublishedSlots() {
        val two=entries.take(2)
        val search=FakeSearch { slot ->
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(listOf(slot)))
        }
        val done=mutableListOf<String>()
        val report=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(listOf("one","two"),done)
        assertEquals(2,report.batch.published)
        assertEquals(2,report.searches)
    }

    @Test fun truncatedEarlierSlotMayUseLaterPublishedSiblingFolderOnce() {
        val two=entries.take(2)
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Found(claims(two),setOf(0))
        }
        val done=mutableListOf<String>()
        val actions=mutableListOf<A0AutoDiscoveryCoordinator.CacheAction>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCacheEvidence={_,e -> actions.add(e.action)})
        assertEquals(1,result.searches)
        assertEquals(listOf("two","one"),done)
        assertEquals(2,result.batch.published)
        assertTrue(A0AutoDiscoveryCoordinator.CacheAction.SIBLING_RESCUE in actions)
    }

    @Test fun truncatedEarlierSlotMustNotBeRescuedFromUnrelatedPeerFolder() {
        val two=entries.take(2)
        val search=FakeSearch { slot ->
            if(slot.trackId=="one")
                A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(two[0])),setOf(0))
            else A0AutoDiscoveryCoordinator.SearchResult.Found(
                alternatePeerClaims(listOf(two[1])))
        }
        val done=mutableListOf<String>()
        val actions=mutableListOf<A0AutoDiscoveryCoordinator.CacheAction>()
        val rescueEvidence=mutableListOf<A0AutoDiscoveryCoordinator.CacheEvidence>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCacheEvidence={_,e ->
                actions.add(e.action)
                if(e.action==A0AutoDiscoveryCoordinator.CacheAction.SIBLING_RESCUE_SKIPPED)
                    rescueEvidence.add(e)
            })
        assertEquals(1,rescueEvidence.size)
        assertEquals(1,rescueEvidence.single().publishedFolderCount)
        assertEquals(0,rescueEvidence.single().samePeerFolderOffers)
        assertEquals(0,rescueEvidence.single().samePeerOffers)
        assertEquals(2,result.searches)
        assertEquals(listOf("two"),done)
        assertTrue(A0AutoDiscoveryCoordinator.CacheAction.SIBLING_RESCUE_SKIPPED in actions)
        assertEquals(listOf(
            A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW,
            A0AutoBatchExecutor.TrackStatus.PUBLISHED),result.batch.results)
    }

    @Test fun readerTeardownFailureOnlyBlocksItsSlotAndAllowsFreshSiblingSearch() {
        val search=FakeSearch { slot ->
            if(slot.trackId=="two")
                A0AutoDiscoveryCoordinator.SearchResult.TeardownIncomplete
            else A0AutoDiscoveryCoordinator.SearchResult.Found(claims(listOf(slot)))
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries.take(3),quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(3,result.searches)
        assertEquals(listOf("one","two","three"),search.requests)
        assertEquals(listOf("one","three"),done)
        assertEquals(listOf(
            A0AutoBatchExecutor.TrackStatus.PUBLISHED,
            A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW,
            A0AutoBatchExecutor.TrackStatus.PUBLISHED),result.batch.results)
        assertEquals(2,result.batch.published)
        assertEquals(1,result.batch.manualReview)
        assertEquals(1,search.closed)
    }

    @Test fun failedLaterDiscoveryKeepsEarlierPublishedTrackVisible() {
        val events=mutableListOf<String>()
        val search=FakeSearch { target ->
            events.add("search:${target.trackId}")
            if(target.trackId=="two")A0AutoDiscoveryCoordinator.SearchResult.Stop
            else A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(entries.filter { it.trackId==target.trackId }))
        }
        val report=A0AutoDiscoveryCoordinator(search,batch(events)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(listOf(
            A0AutoBatchExecutor.TrackStatus.PUBLISHED,
            A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW,
            A0AutoBatchExecutor.TrackStatus.NOT_STARTED,
            A0AutoBatchExecutor.TrackStatus.NOT_STARTED),report.batch.results)
        assertEquals(listOf("search:one","one","search:two"),events)
    }

    @Test fun discardedRelevantShadowSlotNeverPublishesEvenWithStrongRemainingClaim() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                claims(listOf(entries[0])),setOf(0))
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            listOf(entries[0]),quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(listOf(A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW),
            result.batch.results)
        assertTrue(done.isEmpty())
    }

    @Test fun authOrProtocolStopCannotTriggerLateTransferFromEarlierReview() {
        val three=entries.take(3)
        val search=FakeSearch { slot ->
            when(slot.trackId) {
                "one" -> A0AutoDiscoveryCoordinator.SearchResult.NoReply
                "two" -> A0AutoDiscoveryCoordinator.SearchResult.Found(
                    claims(listOf(three[0],three[1])))
                else -> A0AutoDiscoveryCoordinator.SearchResult.Stop
            }
        }
        val published=mutableListOf<String>()
        val report=A0AutoDiscoveryCoordinator(search,batch(published)).run(
            three,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(listOf("two"),published)
        assertEquals(1,report.batch.published)
        assertEquals(listOf("one","two","three"),search.requests)
        assertEquals(A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW,
            report.batch.results[0])
    }

    @Test fun uncertainPublicationBlocksLateRescueOfEarlierSlots() {
        val three=entries.take(3)
        val attempts=mutableListOf<String>()
        val search=FakeSearch { slot ->
            if(slot.trackId=="one")A0AutoDiscoveryCoordinator.SearchResult.NoReply
            else A0AutoDiscoveryCoordinator.SearchResult.Found(claims(
                if(slot.trackId=="two")listOf(three[0],three[1])
                else listOf(three[2])))
        }
        val port=A0AutoBatchExecutor { entry ->
            object:A0AutoAcquisitionExecutor.Port {
                override fun fetch(candidate:A0ObservedCandidateHandoff.Choice,
                    isCancelled:()->Boolean):A0AutoAcquisitionExecutor.FetchResult {
                    val file=File.createTempFile("a0-stop-test-", ".observed")
                    RandomAccessFile(file,"rw").use {
                        it.setLength(entry.durationSeconds!!.toLong()*40_000L)
                    }
                    return A0AutoAcquisitionExecutor.FetchResult.Received(
                        A0VerifiedByteStager.Receipt(
                            file,file.length(),"a".repeat(64),"ID3_HEADER_ONLY"))
                }
                override fun inspect(receipt:A0VerifiedByteStager.Receipt,
                    expectedExtension:String,isCancelled:()->Boolean)=
                    A0AutoPostTransferTechnicalGate.Decoded(
                        A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",
                        entry.durationSeconds!!.toLong()*1000L,320,44100,null,true)
                override fun publish(receipt:A0VerifiedByteStager.Receipt,
                    safeName:String,isCancelled:()->Boolean)
                    :A0AutoAcquisitionExecutor.PublishResult {
                    attempts.add(entry.trackId)
                    return if(entry.trackId=="three")
                        A0AutoAcquisitionExecutor.PublishResult.UNCERTAIN
                    else A0AutoAcquisitionExecutor.PublishResult.DURABLE
                }
                override fun cleanup(receipt:A0VerifiedByteStager.Receipt) {
                    assertTrue(receipt.file.delete())
                }
            }
        }
        val result=A0AutoDiscoveryCoordinator(search,port).run(
            three,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(listOf("two","three"),attempts)
        assertEquals(1,result.batch.published)
        assertEquals(1,result.batch.needsReconciliation)
        assertEquals(A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW,
            result.batch.results[0])
        assertEquals(A0AutoBatchExecutor.TrackStatus.RECONCILE,
            result.batch.results[2])
    }

    @Test fun twoPeerPreacceptFailuresTriggerFreshSearchInsteadOfRepeatedUnsafeSource() {
        val songs=entries
        var searches=0
        val attempted=mutableListOf<Pair<String,String>>()
        val fetched=mutableListOf<String>()
        fun offers(peer:String)=A0ObservedCandidateHandoff(
            Socket(),"local",177,true,searchPhrase=songs[0].query,
            requestedAlbum="SUPER REAL ME",expectedDurationSeconds=120,
            qualityProfile=quality,collectReleaseShadow=true
        ).let { handoff ->
            val files=songs.map { item ->
                A0SoulseekSearchFile(
                    "Shared\\ILLIT\\SUPER REAL ME\\${item.query}.mp3",
                    item.durationSeconds!!.toLong()*40_000L,
                    "mp3",mapOf(0 to 320,1 to item.durationSeconds))
            }
            handoff.offerParsed(A0SoulseekSearchResponse(
                peer,177,files,emptyList(),true,128_000,0),peer)
            handoff.releaseShadowChoices()
        }
        val discovery=FakeSearch {
            searches++
            A0AutoDiscoveryCoordinator.SearchResult.Found(
                offers(if(searches==1) "bad-peer" else "alternate-peer"))
        }
        val executor=A0AutoBatchExecutor { entry ->
            object:A0AutoAcquisitionExecutor.Port {
                override fun fetch(candidate:A0ObservedCandidateHandoff.Choice,
                    isCancelled:()->Boolean):A0AutoAcquisitionExecutor.FetchResult {
                    attempted.add(entry.trackId to candidate.peerUsername)
                    if(candidate.peerUsername=="bad-peer")
                        return A0AutoAcquisitionExecutor.FetchResult.RetryableConnectionFailure
                    val file=File.createTempFile("peer-suppress-", ".mp3")
                    RandomAccessFile(file,"rw").use {
                        it.setLength(entry.durationSeconds!!.toLong()*40_000L)
                    }
                    return A0AutoAcquisitionExecutor.FetchResult.Received(
                        A0VerifiedByteStager.Receipt(file,file.length(),
                            "a".repeat(64),"ID3_HEADER_ONLY"))
                }
                override fun inspect(receipt:A0VerifiedByteStager.Receipt,
                    expectedExtension:String,isCancelled:()->Boolean)=
                    A0AutoPostTransferTechnicalGate.Decoded(
                        A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",
                        entry.durationSeconds!!.toLong()*1000L,320,44100,null,true)
                override fun publish(receipt:A0VerifiedByteStager.Receipt,
                    safeName:String,isCancelled:()->Boolean)
                    :A0AutoAcquisitionExecutor.PublishResult {
                    fetched.add(entry.trackId)
                    return A0AutoAcquisitionExecutor.PublishResult.DURABLE
                }
                override fun cleanup(receipt:A0VerifiedByteStager.Receipt) {
                    assertTrue(receipt.file.delete())
                }
            }
        }
        val result=A0AutoDiscoveryCoordinator(discovery,executor).run(
            songs,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(2,searches)
        assertEquals(2,result.batch.attemptFailures)
        assertEquals(2,result.batch.published)
        assertEquals(listOf("three","four"),fetched)
        assertEquals(listOf("one","two"),attempted.filter {
            it.second=="bad-peer" }.map { it.first })
        assertEquals(listOf("three","four"),attempted.filter {
            it.second=="alternate-peer" }.map { it.first })
    }

    @Test fun screenedOutRepliesBreakTrueNoResponseStreakWithoutHidingSearchAttempts() {
        var calls=0
        val search=FakeSearch {
            calls++
            if(calls==2)
                A0AutoDiscoveryCoordinator.SearchResult.ScreenedOut
            else
                A0AutoDiscoveryCoordinator.SearchResult.NoReply
        }
        val actions=mutableListOf<A0AutoDiscoveryCoordinator.CacheAction>()
        val report=A0AutoDiscoveryCoordinator(
            search,batch(mutableListOf())).run(
            entries.take(3),quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCacheEvidence={_,event -> actions.add(event.action)})
        assertEquals(3,report.searches)
        assertEquals(2,report.noReplySearches)
        assertEquals(1,report.screenedOutSearches)
        assertEquals(1,report.longestNoReplyStreak)
        assertTrue(A0AutoDiscoveryCoordinator.CacheAction.SEARCH_SCREENED_OUT in actions)
        assertEquals(3,report.batch.manualReview)
        assertEquals(1,report.withReservationReleased(true).screenedOutSearches)
    }

    @Test fun threeEmptySearchesAreReportedAsOneStreakWithoutPeerMetadata() {
        val discovery=FakeSearch { A0AutoDiscoveryCoordinator.SearchResult.NoReply }
        val result=A0AutoDiscoveryCoordinator(
            discovery,batch(mutableListOf())).run(
            entries.take(3),quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(3,result.searches)
        assertEquals(3,result.noReplySearches)
        assertEquals(3,result.longestNoReplyStreak)
        assertEquals(3,result.batch.manualReview)
        val copied=result.withReservationReleased(true)
        assertEquals(3,copied.noReplySearches)
        assertEquals(3,copied.longestNoReplyStreak)
        assertTrue(copied.unusedReservationReleased)
    }

    @Test fun noExplicitOptInNeverOpensSearchOrTransfer() {
        val search=FakeSearch {
            throw AssertionError("Network must remain OFF")
        }
        val result=A0AutoDiscoveryCoordinator(search,batch(mutableListOf())).run(
            entries,quality,userRequested=true,optedIn=false,
            isCancelled={false},stillAuthorized={true})
        assertEquals(0,result.searches)
        assertEquals(4,result.batch.manualReview)
        assertEquals(0,search.requests.size)
    }

    @Test fun invalidQueryDoesNotAbortSiblingsAndCanBeRescuedWithoutNewSearch() {
        val two=entries.take(2)
        val search=FakeSearch { slot ->
            if(slot.trackId=="one")
                A0AutoDiscoveryCoordinator.SearchResult.QueryRejected
            else
                A0AutoDiscoveryCoordinator.SearchResult.Found(claims(two))
        }
        val done=mutableListOf<String>()
        val actions=mutableListOf<A0AutoDiscoveryCoordinator.CacheAction>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            two,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true},
            onCacheEvidence={_,event -> actions.add(event.action)})
        assertEquals(listOf("one","two"),search.requests)
        assertEquals(1,result.searches)
        assertEquals(1,result.rejectedQueries)
        assertEquals(0,result.noReplySearches)
        assertTrue(A0AutoDiscoveryCoordinator.CacheAction.QUERY_REJECTED in actions)
        assertEquals(2,result.batch.published)
        assertEquals(setOf("one","two"),done.toSet())
    }

    @Test fun severalUnsearchableQueriesAreNotMiscountedAsEmptyNetworkResponses() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.QueryRejected
        }
        val result=A0AutoDiscoveryCoordinator(
            search,batch(mutableListOf())).run(
            entries.take(3),quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(0,result.searches)
        assertEquals(3,result.rejectedQueries)
        assertEquals(0,result.noReplySearches)
        assertEquals(0,result.longestNoReplyStreak)
        assertEquals(3,result.batch.manualReview)
        assertEquals(3,search.requests.size)
        assertEquals(3,result.withReservationReleased(true).rejectedQueries)
    }

    @Test fun authenticationFailureStopsAllWithoutPartialAutoTransfers() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Stop
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(1,result.searches)
        assertEquals(listOf(
            A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW,
            A0AutoBatchExecutor.TrackStatus.NOT_STARTED,
            A0AutoBatchExecutor.TrackStatus.NOT_STARTED,
            A0AutoBatchExecutor.TrackStatus.NOT_STARTED,
        ),result.batch.results)
        assertTrue(done.isEmpty())
        assertEquals(1,search.closed)
    }

    @Test fun cancellationAfterFirstNetworkQueryDoesNotStartPublishing() {
        var cancelled=false
        val search=FakeSearch {
            cancelled=true
            A0AutoDiscoveryCoordinator.SearchResult.Found(claims(entries))
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={cancelled},stillAuthorized={true})
        assertEquals(1,result.searches)
        assertEquals(1,result.batch.cancelled)
        assertEquals(3,result.batch.notStarted)
        assertTrue(done.isEmpty())
    }
}
