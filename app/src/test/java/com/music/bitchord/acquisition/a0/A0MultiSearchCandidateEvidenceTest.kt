package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.data.settings.DownloadQualityProfile
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class A0MultiSearchCandidateEvidenceTest {
    private val quality=A0SoulseekQualityProfiles.snapshot(DownloadQualityProfile.HIGH_QUALITY)
    private val entries=listOf(
        SoulseekBatchPlan.Entry("one","ILLIT - Magnetic","SUPER REAL ME",161),
        SoulseekBatchPlan.Entry("two","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",160),
    )
    private fun choices(peer:String,track:String,seconds:Int,bitrate:Int=320):
            List<A0ObservedCandidateHandoff.Choice> {
        val handoff=A0ObservedCandidateHandoff(Socket(),"local",177,true,
            searchPhrase="ILLIT - Magnetic",requestedAlbum="SUPER REAL ME",
            expectedDurationSeconds=161,qualityProfile=quality,
            collectReleaseShadow=true)
        val name="Shared\\ILLIT\\SUPER REAL ME\\ILLIT - $track.mp3"
        val file=A0SoulseekSearchFile(name,seconds.toLong()*40_000,
            "mp3",mapOf(0 to bitrate,1 to seconds))
        handoff.offerParsed(A0SoulseekSearchResponse(peer,177,
            listOf(file),emptyList(),true,128_000,0),peer)
        return handoff.releaseShadowChoices()
    }

    @Test fun separateSearchesAccumulateCoverageForBothAlbumSlots() {
        val state=A0MultiSearchCandidateEvidence(entries,quality)
        state.add(choices("peer-a","Magnetic",161))
        val partial=state.snapshot()
        assertEquals(1,partial.preview!!.autoReadyTracks)
        state.add(choices("peer-b","Lucky Girl Syndrome",160))
        val all=state.snapshot()
        assertEquals(2,all.preview!!.autoReadyTracks)
        assertEquals(0,all.blockedSlots)
        assertEquals(2,all.retainedPairs)
        assertFalse(all.canTransfer)
        assertFalse(all.canSearch)
        assertFalse(all.toString().contains("Magnetic"))
    }

    @Test fun repeatedIdenticalPeerAndRemotePathNeverCountAsTwoOffers() {
        val state=A0MultiSearchCandidateEvidence(entries,quality)
        state.add(choices("peer-a","Magnetic",161))
        state.add(choices("peer-a","Magnetic",161))
        val result=state.snapshot()
        assertEquals(1,result.retainedPairs)
        assertEquals(1,result.observedPairs)
        assertEquals(0,result.blockedSlots)
    }

    @Test fun conflictingClaimsFromSameRemotePathForceReview() {
        val state=A0MultiSearchCandidateEvidence(entries,quality)
        state.add(choices("peer-a","Magnetic",161,320))
        state.add(choices("peer-a","Magnetic",161,256))
        val result=state.snapshot()
        assertEquals(1,result.blockedSlots)
        assertEquals(A0ReleaseDiscoveryPlanner.Status.REVIEW,
            result.preview!!.trackDecisions[0].status)
        assertEquals(0,result.preview!!.autoReadyTracks)
    }

    @Test fun selectionForSiblingUsesItsOwnIdentityAndAggregatedClaims() {
        val state=A0MultiSearchCandidateEvidence(entries,quality)
        state.add(choices("p1","Magnetic",161))
        state.add(choices("p2","Lucky Girl Syndrome",160))
        val decisions=state.selectionPreview()
        assertEquals(2,decisions.size)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
            decisions[0].status)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.PROVISIONAL,
            decisions[1].status)
        assertFalse(decisions[0].canTransfer)
        assertFalse(decisions[1].canTransfer)
        assertFalse(decisions.toString().contains("p1"))
    }

    @Test fun contradictoryPerPathEvidencePreventsAutomaticSelection() {
        val state=A0MultiSearchCandidateEvidence(entries,quality)
        state.add(choices("p1","Magnetic",161,320))
        state.add(choices("p1","Magnetic",161,256))
        assertEquals(A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE,
            state.selectionPreview()[0].reason)
        assertEquals(A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW,
            state.selectionPreview()[0].status)
    }

    @Test fun liveCollectionCannotBeConsumedUntilStoppedOrWithDifferentRequest() {
        val evidence=A0MultiSearchCandidateEvidence(entries,quality)
        evidence.add(choices("p1","Magnetic",161))
        assertNull(evidence.executionSnapshot(entries))
        evidence.stop()
        val snapshot=evidence.executionSnapshot(entries)
        assertNotNull(snapshot)
        assertEquals(1,snapshot!!.choicesBySlot[0].size)
        assertEquals(0,snapshot.choicesBySlot[1].size)
        assertFalse(snapshot.toString().contains("Magnetic"))
        assertNull(evidence.executionSnapshot(entries.reversed()))
        assertNull(evidence.executionSnapshot(
            entries.map { it.copy(album="DIFFERENT EDITION") }))
    }

    @Test fun cancellationClosesTheAccumulatorAndKeepsCollectedEvidenceReadOnly() {
        val state=A0MultiSearchCandidateEvidence(entries,quality)
        state.add(choices("peer-a","Magnetic",161))
        state.stop()
        state.add(choices("peer-b","Lucky Girl Syndrome",160))
        assertEquals(1,state.snapshot().retainedPairs)
        assertFalse(state.snapshot().canTransfer)
    }

    @Test fun malformedReleaseEntriesCannotMintAutomaticPermission() {
        val malformed=listOf(entries[0],entries[0])
        val state=A0MultiSearchCandidateEvidence(malformed,quality)
        state.add(choices("peer-a","Magnetic",161))
        val result=state.snapshot()
        assertNull(result.preview)
        assertEquals(2,result.blockedSlots)
        assertFalse(result.canTransfer)
    }
}
