package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Read-only discovery readiness; never authorizes P/F or automatic transfers. */
class A0ReleaseDiscoveryPlannerTest {
    private fun track(n:Int)=A0ReleaseDiscoveryPlanner.Target(
        id="song-$n",expectedDurationSeconds=180+n)
    private fun offer(n:Int,peer:String,folder:String="Artist/Album",claim:String="master-$n",
                      qualityOk:Boolean=true,albumOk:Boolean=true,
                      versionOk:Boolean=true,score:Int=90,
                      seconds:Int=180+n)=A0ReleaseDiscoveryPlanner.Offer(
        slotId="song-$n",peerKey=peer,remotePath="Music/$folder/$n.mp3",
        claimKey=claim,score=score,declaredDurationSeconds=seconds,
        preflight=A0CandidatePreflight.Confidence.STRONG,
        qualityCompatible=qualityOk,albumCompatible=albumOk,
        identityCompatible=true,versionCompatible=versionOk,
        availability=A0PeerAvailability.Tier.READY)
    @Test fun completeAlbumOneFolderNeedsNoPerTrackReSearchInCounterfactual() {
        val tracks=(1..20).map(::track)
        val offers=tracks.mapIndexed { index,_ ->offer(index+1,"peer-A") }
        val result=A0ReleaseDiscoveryPlanner.preview(tracks,offers)
        assertEquals(20,result.autoReadyTracks)
        assertEquals(0,result.manualReviewTracks)
        assertEquals(20,result.bestFolderCoverage)
        assertEquals(1,result.foldersConsidered)
        assertFalse(result.canTransfer)
    }
    @Test fun wrongAlbumLowQualityOrWrongVersionNeverImprovesCoverage() {
        val tracks=(1..5).map(::track)
        val offers=listOf(
            offer(1,"ok"),
            offer(2,"other",albumOk=false),
            offer(3,"other",qualityOk=false),
            offer(4,"other",versionOk=false),
            offer(5,"other",seconds=260))
        val result=A0ReleaseDiscoveryPlanner.preview(tracks,offers)
        assertEquals(1,result.autoReadyTracks)
        assertEquals(4,result.manualReviewTracks)
        assertEquals(1,result.bestFolderCoverage)
    }
    @Test fun distinctStrongVariantsCauseAbstentionInsteadOfGuessing() {
        val result=A0ReleaseDiscoveryPlanner.preview(listOf(track(1)),listOf(
            offer(1,"p1",claim="studio",score=90),
            offer(1,"p2",claim="different-bytes",score=87)))
        assertEquals(0,result.autoReadyTracks)
        assertEquals(1,result.ambiguousTracks)
        assertEquals(1,result.manualReviewTracks)
    }
    @Test fun repeatedClaimFromDifferentPeersBecomesSequentialFallbackOnly() {
        val result=A0ReleaseDiscoveryPlanner.preview(listOf(track(1)),listOf(
            offer(1,"a"),offer(1,"b"),offer(1,"c"),offer(1,"d"),offer(1,"e")))
        assertEquals(1,result.autoReadyTracks)
        assertEquals(3,result.trackDecisions.single().alternativePeers.size)
        assertEquals(3,result.trackDecisions.single().alternativePeers.toSet().size)
        assertFalse(result.canTransfer)
    }
    @Test fun sourceFolderCoverageDoesNotConflateSameFolderNameAtDifferentPeers() {
        val tracks=(1..3).map(::track)
        val offers=listOf(offer(1,"one"),offer(2,"two"),offer(3,"three"))
        val result=A0ReleaseDiscoveryPlanner.preview(tracks,offers)
        assertEquals(1,result.bestFolderCoverage)
        assertEquals(3,result.foldersConsidered)
        assertEquals(3,result.autoReadyTracks)
    }
    @Test fun missingDurationOrDuplicateSlotRefusesAutoReadiness() {
        val tracks=listOf(track(1).copy(expectedDurationSeconds=null),track(2))
        val result=A0ReleaseDiscoveryPlanner.preview(tracks,listOf(offer(1,"a"),offer(2,"a")))
        assertEquals(1,result.autoReadyTracks)
        assertEquals(1,result.manualReviewTracks)
        assertEquals(0,result.ambiguousTracks)
        assertEquals(0,A0ReleaseDiscoveryPlanner.preview(listOf(track(1),track(1)),
            listOf(offer(1,"a"))).autoReadyTracks)
    }
    @Test fun reasonsExplainZeroReadinessWithoutLeakingOfferIdentities() {
        val tracks=listOf(track(1),track(2),track(3))
        val offers=listOf(
            offer(1,"peerA",qualityOk=false),
            offer(2,"peerB",albumOk=false),
            offer(3,"peerC",seconds=600)
        )
        val result=A0ReleaseDiscoveryPlanner.preview(tracks,offers)
        assertEquals(0,result.autoReadyTracks)
        assertEquals(3,result.manualReviewTracks)
        assertEquals(1,result.rejections[A0ReleaseDiscoveryPlanner.Rejection.QUALITY])
        assertEquals(1,result.rejections[A0ReleaseDiscoveryPlanner.Rejection.ALBUM])
        assertEquals(1,result.rejections[A0ReleaseDiscoveryPlanner.Rejection.DURATION_MISMATCH])
        assertEquals(3,result.rejections.values.sum())
        assertFalse(result.canTransfer)
        assertFalse(result.toString().contains("peerA"))
    }

    @Test fun missingDurationIsNotMistakenForBadAudioOrMissingPeers() {
        val result=A0ReleaseDiscoveryPlanner.preview(
            listOf(track(1).copy(expectedDurationSeconds=null)),
            listOf(offer(1,"testPeer")))
        assertEquals(0,result.autoReadyTracks)
        assertEquals(1,result.rejections[A0ReleaseDiscoveryPlanner.Rejection.MISSING_DURATION])
    }

    @Test(timeout=5000) fun twentyFourThousandOffersRemainBoundedAndNoUnexpectedTransfer() {
        val tracks=(1..64).map(::track)
        val offers=(0 until 24_000).map { index ->
            offer((index%64)+1,"peer-${index%400}",folder="Artist/Album-${index%4}",
                claim="master-${index%64}",score=90)
        }
        val result=A0ReleaseDiscoveryPlanner.preview(tracks,offers)
        assertTrue(result.examinedOffers<=A0ReleaseDiscoveryPlanner.MAX_OFFERS)
        assertTrue(result.autoReadyTracks<=64)
        assertFalse(result.canTransfer)
    }
}
