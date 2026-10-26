package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

/** Uses actual bounded A0 search replies; never calls P/F. */
class A0ReleaseDiscoveryShadowAdapterTest {
    private val profile=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private fun reply(vararg files:A0SoulseekSearchFile)=A0SoulseekSearchResponse(
        "peer",177,files.toList(),emptyList(),true,128000,0)
    private fun file(name:String,seconds:Int)=A0SoulseekSearchFile(
        "Shared\\ILLIT\\SUPER REAL ME\\$name.mp3",
        seconds.toLong()*40_000L,"mp3",mapOf(0 to 320,1 to seconds))
    private val entries=listOf(
        SoulseekBatchPlan.Entry("m","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("l","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",160),
    )
    @Test fun onePeerResponseCoveringTwoTracksIsObservedWithoutAutoDownload() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=profile)
        handoff.offerParsed(reply(
            file("ILLIT - Magnetic",161),
            file("ILLIT - Lucky Girl Syndrome",160)),"peer")
        val shadow=A0ReleaseDiscoveryShadowAdapter.preview(entries,handoff.choices(),profile)
        assertEquals(2,shadow.autoReadyTracks)
        assertEquals(2,shadow.bestFolderCoverage)
        assertFalse(shadow.canTransfer)
    }
    @Test fun unrelatedTrackAndWrongQualityCannotBoostAlbumCoverage() {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=profile)
        handoff.offerParsed(reply(
            file("ILLIT - Magnetic",161),
            A0SoulseekSearchFile("Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Lucky Girl Syndrome.flac",
                24_000_000L,"flac",mapOf(1 to 160,4 to 44100,5 to 16))
        ),"peer")
        val shadow=A0ReleaseDiscoveryShadowAdapter.preview(entries,handoff.choices(),profile)
        assertEquals(1,shadow.autoReadyTracks)
        assertEquals(1,shadow.bestFolderCoverage)
        assertEquals(1,shadow.manualReviewTracks)
    }

    @Test(timeout=8000)
    fun sixtyFourTrackReleaseStillSeesValidSongAfterFirst128UnrelatedOffers() {
        val tracks=(1..64).map { n ->
            SoulseekBatchPlan.Entry("slot-$n","ILLIT - Track $n","SUPER REAL ME",161)
        }
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Track 1",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=profile,
            collectReleaseShadow=true)
        repeat(3) { group ->
            val peer="noisy-$group"
            val files=(1..64).map { n ->
                A0SoulseekSearchFile(
                    "Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Noise ${group*64+n}.mp3",
                    6_440_000L,"mp3",mapOf(0 to 320,1 to 161))
            }
            handoff.offerParsed(A0SoulseekSearchResponse(
                peer,177,files,emptyList(),true,128_000,0),peer)
        }
        handoff.offerParsed(A0SoulseekSearchResponse(
            "late",177,listOf(file("ILLIT - Track 64",161)),
            emptyList(),true,128_000,0),"late")
        assertEquals(193,handoff.releaseShadowChoices().size)
        val result=A0ReleaseDiscoveryShadowAdapter.preview(
            tracks,handoff.releaseShadowChoices(),profile)
        assertEquals(1,result.autoReadyTracks)
        assertEquals(A0ReleaseDiscoveryPlanner.Status.SUGGESTED,
            result.trackDecisions[63].status)
        assertEquals(1,result.bestFolderCoverage)
        assertTrue(result.examinedOffers<=A0ReleaseDiscoveryPlanner.MAX_OFFERS)
        assertFalse(result.canTransfer)
    }

    @Test(timeout=8000)
    fun lateCompatibleMp3IsNotStarvedBy128WrongQualityFlacClaims() {
        val tracks=(1..64).map { n ->
            SoulseekBatchPlan.Entry("slot-$n","ILLIT - Track $n","SUPER REAL ME",161)
        }
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Track 64",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=profile,
            collectReleaseShadow=true)
        repeat(128) { i ->
            val peer="flac-$i"
            handoff.offerParsed(A0SoulseekSearchResponse(peer,177,listOf(
                A0SoulseekSearchFile(
                    "Shared\\ILLIT\\SUPER REAL ME\\ILLIT - Track 64.flac",
                    25_000_000L,"flac",mapOf(1 to 161,4 to 44_100,5 to 16))),
                emptyList(),true,128_000,0),peer)
        }
        handoff.offerParsed(A0SoulseekSearchResponse("mp3",177,listOf(
            file("ILLIT - Track 64",161)),emptyList(),true,128_000,0),"mp3")
        assertEquals(129,handoff.releaseShadowChoices().size)
        val preview=A0ReleaseDiscoveryShadowAdapter.preview(
            tracks,handoff.releaseShadowChoices(),profile)
        assertEquals(1,preview.autoReadyTracks)
        assertEquals(A0ReleaseDiscoveryPlanner.Status.SUGGESTED,
            preview.trackDecisions[63].status)
        assertFalse(preview.canTransfer)
    }

    @Test fun titleGateOnlyRejectsCandidatesRankerCanNeverAccept() {
        val inputs=listOf(
            Triple("ILLIT - More","ILLIT - More.mp3","Music/ILLIT/BREAK EVEN/ILLIT - More.mp3"),
            Triple("ILLIT - More","More.mp3","Music/ILLIT/BREAK EVEN/More.mp3"),
            Triple("ILLIT - Lucky Girl Syndrome","02 Lucky Girl Syndrome.flac","Music/ILLIT/Lucky Girl Syndrome.flac"),
            Triple("NewJeans - Hype Boy","NewJeans - Hype Boy.mp3","Music/NewJeans/NewJeans - Hype Boy.mp3"),
            Triple("ILLIT - Café","ILLIT - Cafe.mp3","Music/ILLIT/ILLIT - Cafe.mp3"),
            Triple("ILLIT - More","ILLIT - Something Else.mp3","Music/ILLIT/BREAK EVEN/ILLIT - Something Else.mp3"),
            Triple("ILLIT - More","ILLIT - More More.mp3","Music/ILLIT/BREAK EVEN/ILLIT - More More.mp3")
        )
        for((query,name,path) in inputs) {
            val ranked=A0CandidateRank.rank(query,name,320,
                remotePath=path,format=name.substringAfterLast('.'),
                declaredDurationSeconds=160,expectedDurationSeconds=160,
                sizeBytes=6_400_000L)
            val tokens=A0ReleaseDiscoveryShadowAdapter.titleTokens(query)
            val fileTokens=A0ReleaseDiscoveryShadowAdapter.fileTokens(name)
            if(ranked.match==A0CandidateRank.Match.ALL_TERMS) {
                assertTrue("Never drop fully matching $query",fileTokens.containsAll(tokens))
            }
        }
        assertFalse(A0ReleaseDiscoveryShadowAdapter.fileTokens("ILLIT - Noise.mp3")
            .containsAll(A0ReleaseDiscoveryShadowAdapter.titleTokens("ILLIT - More")))
    }
}
