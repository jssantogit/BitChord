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

    @Test fun firstQueryCoveringAllFourAlbumSlotsAvoidsThreeRedundantSearches() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Found(claims(entries))
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(1,result.searches)
        assertEquals(listOf("one"),search.requests)
        assertEquals(4,result.batch.published)
        assertEquals(entries.map{it.trackId},done)
        assertEquals(1,search.closed)
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

    @Test fun authenticationFailureStopsAllWithoutPartialAutoTransfers() {
        val search=FakeSearch {
            A0AutoDiscoveryCoordinator.SearchResult.Stop
        }
        val done=mutableListOf<String>()
        val result=A0AutoDiscoveryCoordinator(search,batch(done)).run(
            entries,quality,userRequested=true,optedIn=true,
            isCancelled={false},stillAuthorized={true})
        assertEquals(1,result.searches)
        assertEquals(4,result.batch.manualReview)
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
