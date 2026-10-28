package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.settings.DownloadQualityProfile
import com.music.bitchord.download.SoulseekBatchPlan
import java.io.File
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/**
 * The future Android worker can supply real I/O, but NEVER a policy bypass.
 * The orchestrator must be safe even if a transport fails or races logout.
 */
class A0AutoAcquisitionExecutorTest {
    private val target=SoulseekBatchPlan.Entry(
        "magnetic","ILLIT - Magnetic","SUPER REAL ME",161)
    private val quality=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val bytes=6_440_000L
    private fun choices(peer:String):List<A0ObservedCandidateHandoff.Choice> {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase=target.query,requestedAlbum=target.album,
            expectedDurationSeconds=161,qualityProfile=quality,
            collectReleaseShadow=true)
        handoff.offerParsed(A0SoulseekSearchResponse(
            peer,177,listOf(A0SoulseekSearchFile(
                "Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Magnetic.mp3",bytes,
                "mp3",mapOf(0 to 320,1 to 161))),
            emptyList(),true,128000,0),peer)
        return handoff.releaseShadowChoices()
    }
    private val decoded=A0AutoPostTransferTechnicalGate.Decoded(
        codec=A0CanonicalAcquisitionIntent.Codec.MP3,
        extension="mp3",durationMillis=161_000L,
        bitrateKbps=320,sampleRateHz=44100,bitDepth=null,completed=true)
    private class FakePort:A0AutoAcquisitionExecutor.Port {
        var fetches=0
        var publishes=0
        var cleanups=0
        var inspections=0
        var failCount=0
        var afterFetch:(()->Unit)?=null
        var decoded:A0AutoPostTransferTechnicalGate.Decoded?=null
        var publicationSucceeds=true
        override fun fetch(
            candidate:A0ObservedCandidateHandoff.Choice,
            isCancelled:()->Boolean,
        ):A0AutoAcquisitionExecutor.FetchResult {
            fetches++
            if(fetches<=failCount)return A0AutoAcquisitionExecutor.FetchResult.RetryableConnectionFailure
            afterFetch?.invoke()
            val scratch=File.createTempFile("a0-auto-test-", ".observed")
            java.io.RandomAccessFile(scratch,"rw").use { it.setLength(6_440_000L) }
            return A0AutoAcquisitionExecutor.FetchResult.Received(
                A0VerifiedByteStager.Receipt(scratch,
                    6_440_000L,"a".repeat(64),"ID3_HEADER_ONLY"))
        }
        override fun inspect(
            receipt:A0VerifiedByteStager.Receipt,
            expectedExtension:String,
            isCancelled:()->Boolean,
        ):A0AutoPostTransferTechnicalGate.Decoded? {
            inspections++
            return decoded
        }
        override fun publish(
            receipt:A0VerifiedByteStager.Receipt,
            safeName:String,
            isCancelled:()->Boolean,
        ):Boolean {
            publishes++
            return publicationSucceeds
        }
        override fun cleanup(receipt:A0VerifiedByteStager.Receipt) {
            cleanups++
            assertTrue("Each temporary receipt must be discarded",receipt.file.delete())
        }
    }

    @Test fun defaultOffOrMissingUserTapNeverRequestsPeerBytes() {
        val port=FakePort().apply {decoded=this@A0AutoAcquisitionExecutorTest.decoded}
        val engine=A0AutoAcquisitionExecutor(port)
        val candidates=choices("p1")
        val disabled=engine.run(target,candidates,quality,
            userRequested=true,optedIn=false,stillAuthorized={true})
        val untapped=engine.run(target,candidates,quality,
            userRequested=false,optedIn=true,stillAuthorized={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.MANUAL_REVIEW,disabled.status)
        assertEquals(A0AutoAcquisitionExecutor.Status.MANUAL_REVIEW,untapped.status)
        assertEquals(0,port.fetches)
        assertEquals(0,port.publishes)
    }

    @Test fun goodSingleCandidateFlowsThroughDecodedGateThenPublicationAndCleanup() {
        val port=FakePort().apply {decoded=this@A0AutoAcquisitionExecutorTest.decoded}
        val result=A0AutoAcquisitionExecutor(port).run(
            target,choices("p1"),quality,userRequested=true,optedIn=true,stillAuthorized={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.PUBLISHED,result.status)
        assertEquals(1,result.attempts)
        assertEquals(1,port.inspections)
        assertEquals(1,port.publishes)
        assertEquals(1,port.cleanups)
        assertFalse(result.toString().contains("ILLIT"))
    }

    @Test fun transportConnectionFailuresRetryOnlyThreeEquivalentPeersInSeries() {
        val port=FakePort().apply {
            decoded=this@A0AutoAcquisitionExecutorTest.decoded
            failCount=2
        }
        val observed=(1..3).flatMap { choices("p$it") }
        val result=A0AutoAcquisitionExecutor(port).run(
            target,observed,quality,userRequested=true,optedIn=true,stillAuthorized={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.PUBLISHED,result.status)
        assertEquals(3,result.attempts)
        assertEquals(3,port.fetches)
        assertEquals(1,port.publishes)
        assertEquals(1,port.cleanups)
    }

    @Test fun corruptDecodedAudioStopsWithoutPublishingOrTryingOtherPeers() {
        val port=FakePort().apply {
            decoded=this@A0AutoAcquisitionExecutorTest.decoded.copy(durationMillis=194_000L)
        }
        val result=A0AutoAcquisitionExecutor(port).run(
            target,choices("p1")+choices("p2"),quality,
            userRequested=true,optedIn=true,stillAuthorized={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.MANUAL_REVIEW,result.status)
        assertEquals(A0AutoAcquisitionExecutor.Reason.TECHNICAL_REJECTION,result.reason)
        assertEquals(1,port.fetches)
        assertEquals(0,port.publishes)
        assertEquals(1,port.cleanups)
    }

    @Test fun accountRevocationAfterTransportPreventsPublicationButCleansScratch() {
        var allowed=true
        val port=FakePort().apply {
            decoded=this@A0AutoAcquisitionExecutorTest.decoded
            afterFetch={allowed=false}
        }
        val result=A0AutoAcquisitionExecutor(port).run(
            target,choices("p1"),quality,
            userRequested=true,optedIn=true,stillAuthorized={allowed})
        assertEquals(A0AutoAcquisitionExecutor.Status.CANCELLED,result.status)
        assertEquals(0,port.publishes)
        assertEquals(1,port.cleanups)
    }

    @Test fun ambiguousVersionsAndDisabledSourceReturnToManualWithoutIOMutation() {
        val port=FakePort()
        val ambiguous=choices("p1")+choices("p2")
        // Different declared filesize but otherwise strongly matching title.
        val result=A0AutoAcquisitionExecutor(port).run(
            target,ambiguous,quality,userRequested=true,optedIn=false,stillAuthorized={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.MANUAL_REVIEW,result.status)
        assertEquals(0,port.fetches)
    }

    @Test fun cancelledBeforeStartNeverTouchesPeerAndInvalidPublicationNeverRetries() {
        val port=FakePort().apply {
            decoded=this@A0AutoAcquisitionExecutorTest.decoded
            publicationSucceeds=false
        }
        val executor=A0AutoAcquisitionExecutor(port)
        val cancelled=executor.run(target,choices("p1"),quality,
            userRequested=true,optedIn=true,stillAuthorized={true},isCancelled={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.CANCELLED,cancelled.status)
        assertEquals(0,port.fetches)
        val failure=executor.run(target,choices("p1"),quality,
            userRequested=true,optedIn=true,stillAuthorized={true})
        assertEquals(A0AutoAcquisitionExecutor.Status.MANUAL_REVIEW,failure.status)
        assertEquals(A0AutoAcquisitionExecutor.Reason.PUBLICATION_FAILURE,failure.reason)
        assertEquals(1,port.fetches)
        assertEquals(1,port.cleanups)
    }
}
