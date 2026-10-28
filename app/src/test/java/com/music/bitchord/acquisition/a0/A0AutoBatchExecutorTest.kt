package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQualityProfile
import com.music.bitchord.download.SoulseekBatchPlan
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0AutoBatchExecutorTest {
    private val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val entries=listOf(
        SoulseekBatchPlan.Entry("1","ILLIT - My World","SUPER REAL ME",120),
        SoulseekBatchPlan.Entry("2","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("3","ILLIT - Midnight Fiction","SUPER REAL ME",153),
        SoulseekBatchPlan.Entry("4","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",160)
    )
    private fun candidates():List<A0ObservedCandidateHandoff.Choice> {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=entries[0].query,requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=120,qualityProfile=profile,collectReleaseShadow=true)
        val files=entries.map { track ->
            A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\${track.query}.mp3",
                track.durationSeconds!!.toLong()*40_000L,"mp3",
                mapOf(0 to 320,1 to track.durationSeconds))
        }
        handoff.offerParsed(A0SoulseekSearchResponse("album-peer",177,
            files,emptyList(),true,128_000,0),"album-peer")
        return handoff.releaseShadowChoices()
    }
    private fun factory(done:MutableList<String>):
            (SoulseekBatchPlan.Entry)->A0AutoAcquisitionExecutor.Port =
        {entry ->
            object:A0AutoAcquisitionExecutor.Port {
                override fun fetch(
                    candidate:A0ObservedCandidateHandoff.Choice,
                    isCancelled:()->Boolean,
                ):A0AutoAcquisitionExecutor.FetchResult {
                    val file=File.createTempFile("a0-album-test-", ".observed")
                    RandomAccessFile(file,"rw").use {
                        it.setLength(entry.durationSeconds!!.toLong()*40_000L)
                    }
                    return A0AutoAcquisitionExecutor.FetchResult.Received(
                        A0VerifiedByteStager.Receipt(
                            file,file.length(),"a".repeat(64),"ID3_HEADER_ONLY"))
                }
                override fun inspect(
                    receipt:A0VerifiedByteStager.Receipt,
                    expectedExtension:String,
                    isCancelled:()->Boolean,
                )=A0AutoPostTransferTechnicalGate.Decoded(
                    A0CanonicalAcquisitionIntent.Codec.MP3,"mp3",
                    entry.durationSeconds!!.toLong()*1000L,320,44100,null,true)
                override fun publish(
                    receipt:A0VerifiedByteStager.Receipt,
                    safeName:String,
                    isCancelled:()->Boolean,
                ):A0AutoAcquisitionExecutor.PublishResult {
                    done.add(entry.trackId)
                    return A0AutoAcquisitionExecutor.PublishResult.DURABLE
                }
                override fun cleanup(receipt:A0VerifiedByteStager.Receipt) {
                    assertTrue(receipt.file.delete())
                }
            }
        }

    @Test fun oneAlbumSearchCanSupplyFourSerializedPerSlotDownloadJobs() {
        val done=mutableListOf<String>()
        val engine=A0AutoBatchExecutor(factory(done))
        val shared=candidates()
        val report=engine.run(entries,entries.map { shared },emptySet(),profile,
            userRequested=true,optedIn=true,isCancelled={false},
            stillAuthorized={true})
        assertEquals(4,report.published)
        assertEquals(0,report.manualReview)
        assertEquals(4,report.attempts)
        assertEquals(entries.map {it.trackId},done)
        assertFalse(report.toString().contains("ILLIT"))
    }

    @Test fun missingSongRemainsManualWhileOthersCanPublish() {
        val done=mutableListOf<String>()
        val offered=candidates().filterNot {
            it.claim.filename.contains("Midnight Fiction")
        }
        val report=A0AutoBatchExecutor(factory(done)).run(
            entries,entries.map { offered },emptySet(),profile,
            userRequested=true,optedIn=true,isCancelled={false},
            stillAuthorized={true})
        assertEquals(3,report.published)
        assertEquals(1,report.manualReview)
        assertEquals(listOf("1","2","4"),done)
    }

    @Test fun accountRevokedAfterFirstPublicationStopsRemainingBatch() {
        val done=mutableListOf<String>()
        val report=A0AutoBatchExecutor(factory(done)).run(
            entries,entries.map { candidates() },emptySet(),profile,
            userRequested=true,optedIn=true,isCancelled={false},
            stillAuthorized={done.isEmpty()})
        assertEquals(1,report.published)
        assertEquals(1,report.cancelled)
        assertEquals(2,report.notStarted)
        assertEquals(listOf("1"),done)
    }

    @Test fun defaultOffAndMalformedAlbumNeverCreateTransportPorts() {
        var opened=0
        val factory:(SoulseekBatchPlan.Entry)->A0AutoAcquisitionExecutor.Port={
            opened++
            throw IllegalStateException("Port should not open")
        }
        val engine=A0AutoBatchExecutor(factory)
        val disabled=engine.run(entries,entries.map{candidates()},
            emptySet(),profile,true,false,{false},{true})
        assertEquals(4,disabled.manualReview)
        val invalid=entries.toMutableList().also {it[1]=it[0]}
        val malformed=engine.run(invalid,entries.map{candidates()},
            emptySet(),profile,true,true,{false},{true})
        assertEquals(4,malformed.manualReview)
        assertEquals(0,opened)
    }

    @Test fun samePeerFileCannotSatisfyTwoDistinctCanonicalSlots() {
        val first=entries[1]
        val reprise=first.copy(trackId="reprise")
        val two=listOf(first,reprise)
        val source=candidates()
        val published=mutableListOf<String>()
        val report=A0AutoBatchExecutor(factory(published)).run(
            two,two.map { source },emptySet(),profile,
            userRequested=true,optedIn=true,isCancelled={false},
            stillAuthorized={true})
        assertEquals(1,report.published)
        assertEquals(1,report.manualReview)
        assertEquals(listOf(first.trackId),published)
    }

    @Test fun truncatedSlotIsNotAutomaticallyDownloaded() {
        val done=mutableListOf<String>()
        val report=A0AutoBatchExecutor(factory(done)).run(
            entries,entries.map{candidates()},setOf(1),profile,
            userRequested=true,optedIn=true,isCancelled={false},
            stillAuthorized={true})
        assertEquals(3,report.published)
        assertEquals(1,report.manualReview)
        assertFalse("Clipped track may not run",done.contains("2"))
    }
}
