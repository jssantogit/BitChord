package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import org.junit.Assert.*
import org.junit.Test

class A0AutomationDiscoveryRoadmapTest {
    private val entries=(1..4).map { n ->
        SoulseekBatchPlan.Entry("track-$n","ILLIT - Track $n","SUPER REAL ME",160)
    }
    private fun previewWith(vararg statuses:A0ReleaseDiscoveryPlanner.Status):
            A0ReleaseDiscoveryPlanner.Preview {
        val statusesBySlot=entries.mapIndexed { i,entry ->
            A0ReleaseDiscoveryPlanner.TrackDecision(entry.trackId,
                statuses.getOrElse(i){A0ReleaseDiscoveryPlanner.Status.REVIEW},
                emptyList())
        }
        return A0ReleaseDiscoveryPlanner.Preview(
            statusesBySlot.count {
                it.status==A0ReleaseDiscoveryPlanner.Status.SUGGESTED },
            statusesBySlot.count {
                it.status!=A0ReleaseDiscoveryPlanner.Status.SUGGESTED },
            statusesBySlot.count {
                it.status==A0ReleaseDiscoveryPlanner.Status.AMBIGUOUS },
            0,0,0,statusesBySlot)
    }

    @Test fun oneUserRequestedReleaseStartsWithOneUnsearchedSlotOnly() {
        val plan=A0AutomationDiscoveryRoadmap.preview(entries,emptySet(),emptyList())
        assertEquals(A0AutomationDiscoveryRoadmap.State.SEARCH_MORE,plan.state)
        assertEquals(0,plan.nextSearchSlotIndex)
        assertEquals(0,plan.provisionallyCovered)
        assertFalse(plan.canSearch)
        assertFalse(plan.canTransfer)
    }

    @Test fun observedSiblingTracksAvoidUnnecessaryFutureQueriesInPreview() {
        val plan=A0AutomationDiscoveryRoadmap.preview(entries,setOf("track-1"),
            listOf(previewWith(
                A0ReleaseDiscoveryPlanner.Status.SUGGESTED,
                A0ReleaseDiscoveryPlanner.Status.SUGGESTED,
                A0ReleaseDiscoveryPlanner.Status.REVIEW,
                A0ReleaseDiscoveryPlanner.Status.REVIEW)))
        assertEquals(2,plan.provisionallyCovered)
        assertEquals(2,plan.nextSearchSlotIndex)
        assertEquals(A0AutomationDiscoveryRoadmap.State.SEARCH_MORE,plan.state)
        assertFalse(plan.canTransfer)
    }

    @Test fun oneSourceCoveringTheEntireAlbumStopsSearchWithoutAuthorizingDownload() {
        val plan=A0AutomationDiscoveryRoadmap.preview(entries,setOf("track-1"),
            listOf(previewWith(*Array(4){
                A0ReleaseDiscoveryPlanner.Status.SUGGESTED })))
        assertEquals(A0AutomationDiscoveryRoadmap.State.PROVISIONAL_COVERAGE,plan.state)
        assertEquals(4,plan.provisionallyCovered)
        assertNull(plan.nextSearchSlotIndex)
        assertFalse(plan.canSearch)
        assertFalse(plan.canTransfer)
    }

    @Test fun fullySearchedButStillUncertainTrackFallsBackToHumanSelection() {
        val plan=A0AutomationDiscoveryRoadmap.preview(entries,entries.map{it.trackId}.toSet(),
            listOf(previewWith(A0ReleaseDiscoveryPlanner.Status.SUGGESTED)))
        assertEquals(A0AutomationDiscoveryRoadmap.State.MANUAL_REVIEW,plan.state)
        assertEquals(3,plan.manualReviewSlots)
        assertNull(plan.nextSearchSlotIndex)
    }

    @Test fun conflictingSnapshotsAreNeverCombinedIntoFalseCertainty() {
        val plan=A0AutomationDiscoveryRoadmap.preview(entries,setOf("track-1"),
            listOf(previewWith(A0ReleaseDiscoveryPlanner.Status.SUGGESTED),
                previewWith(A0ReleaseDiscoveryPlanner.Status.SUGGESTED)))
        assertEquals(A0AutomationDiscoveryRoadmap.State.SEARCH_MORE,plan.state)
        assertEquals(0,plan.provisionallyCovered)
        assertEquals(1,plan.manualReviewSlots)
        assertEquals(1,plan.nextSearchSlotIndex)
        assertFalse(plan.canTransfer)
    }

    @Test fun ambiguityAndCancellationNeverRequestAnotherNetworkOperation() {
        val ambiguous=A0AutomationDiscoveryRoadmap.preview(entries,
            setOf("track-1","track-2","track-3","track-4"),
            listOf(previewWith(A0ReleaseDiscoveryPlanner.Status.AMBIGUOUS)))
        assertEquals(A0AutomationDiscoveryRoadmap.State.MANUAL_REVIEW,ambiguous.state)
        assertFalse(ambiguous.canSearch)
        val cancelled=A0AutomationDiscoveryRoadmap.preview(entries,
            emptySet(),emptyList(),cancelled=true)
        assertEquals(A0AutomationDiscoveryRoadmap.State.STOPPED,cancelled.state)
        assertNull(cancelled.nextSearchSlotIndex)
        assertFalse(cancelled.canTransfer)
    }

    @Test fun invalidOrDuplicateCanonicalSlotsAndExternalSearchesAbstain() {
        val duplicate=entries.toMutableList().also { it[1]=it[0] }
        assertEquals(A0AutomationDiscoveryRoadmap.State.MANUAL_REVIEW,
            A0AutomationDiscoveryRoadmap.preview(duplicate,emptySet(),emptyList()).state)
        assertEquals(A0AutomationDiscoveryRoadmap.State.MANUAL_REVIEW,
            A0AutomationDiscoveryRoadmap.preview(entries,setOf("unknown"),emptyList()).state)
        assertEquals(A0AutomationDiscoveryRoadmap.State.MANUAL_REVIEW,
            A0AutomationDiscoveryRoadmap.preview(entries,emptySet(),listOf(
                A0ReleaseDiscoveryPlanner.preview(listOf(
                    A0ReleaseDiscoveryPlanner.Target("unrelated",160)),emptyList())
            )).state)
        assertFalse(A0AutomationDiscoveryRoadmap.preview(entries,emptySet(),emptyList())
            .toString().contains("ILLIT"))
    }

    @Test fun singleTrackUsesSameSequentialDecisionWithoutAlbumSpecialCases() {
        val plan=A0AutomationDiscoveryRoadmap.preview(entries.take(1),
            emptySet(),emptyList())
        assertEquals(0,plan.nextSearchSlotIndex)
        assertEquals(1,plan.requestedSlots)
        assertFalse(plan.canTransfer)
    }
}
