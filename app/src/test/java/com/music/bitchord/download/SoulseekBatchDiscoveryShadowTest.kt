package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0ReleaseDiscoveryPlanner
import com.music.bitchord.acquisition.a0.A0CandidatePreflight
import com.music.bitchord.acquisition.a0.A0PeerAvailability
import org.junit.Assert.*
import org.junit.Test

class SoulseekBatchDiscoveryShadowTest {
    private fun entries(size:Int):List<SoulseekBatchPlan.Entry> =
        (1..size).map { n -> SoulseekBatchPlan.Entry(
            "track-$n","Artist - Track $n","Album",180+n) }
    @Test fun twentyTrackCatalogBatchHasAReleaseCoveragePreview() {
        val planned=entries(20)
        val offers=(1..20).map { n -> A0ReleaseDiscoveryPlanner.Offer(
            slotId="track-$n",peerKey="peer",remotePath="shared/Artist/Album/$n.mp3",
            claimKey="track-$n-master",score=95,declaredDurationSeconds=180+n,
            preflight=A0CandidatePreflight.Confidence.STRONG,
            qualityCompatible=true,albumCompatible=true,identityCompatible=true,
            versionCompatible=true,availability=A0PeerAvailability.Tier.READY) }
        val result=SoulseekBatchPlan.previewDiscovery(planned,offers)
        assertEquals(20,result.autoReadyTracks)
        assertEquals(20,result.bestFolderCoverage)
        assertFalse(result.canTransfer)
    }
    @Test fun missingTrackIsNotInventedFromFullFolderName() {
        val planned=entries(4)
        val offer=A0ReleaseDiscoveryPlanner.Offer(
            slotId="track-1",peerKey="peer",remotePath="Artist/Album/01.mp3",
            claimKey="one",score=95,declaredDurationSeconds=181,
            preflight=A0CandidatePreflight.Confidence.STRONG,
            qualityCompatible=true,albumCompatible=true,identityCompatible=true,
            versionCompatible=true,availability=A0PeerAvailability.Tier.READY)
        val result=SoulseekBatchPlan.previewDiscovery(planned,listOf(offer))
        assertEquals(1,result.autoReadyTracks)
        assertEquals(3,result.manualReviewTracks)
    }
}
