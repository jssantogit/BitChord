package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0AutoBatchExecutor
import org.junit.Assert.*
import org.junit.Test

/** Regression boundary: a partial auto album may only resume uncertain slots manually. */
class SoulseekAutoReviewPlanTest {
    private val done=A0AutoBatchExecutor.TrackStatus.PUBLISHED
    private val review=A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW
    private val uncertain=A0AutoBatchExecutor.TrackStatus.RECONCILE
    private val cancelled=A0AutoBatchExecutor.TrackStatus.CANCELLED
    private val notStarted=A0AutoBatchExecutor.TrackStatus.NOT_STARTED
    private fun entries(n:Int)=List(n) { i ->
        SoulseekBatchPlan.Entry(
            trackId="canonical-$i",
            query="ILLIT - Track ${i+1}",
            album="Super Real Me",
            durationSeconds=180+i,
        )
    }

    @Test fun mixedAlbumOffersOnlyManualSlotsInCanonicalReleaseOrder() {
        val original=entries(4)
        val plan=SoulseekAutoReviewPlan.from(
            original,listOf(done,review,done,review))
        assertNotNull(plan)
        assertEquals(listOf(1,3),plan!!.manualIndices)
        assertEquals(listOf(original[1],original[3]),plan.manualEntries)
        assertEquals(2,plan.published)
        assertEquals(2,plan.needsManualReview)
        assertEquals(0,plan.needsReconciliation)
        assertTrue(plan.manualAllowed)
        assertEquals(original.map { it.query },plan.rows.map { it.entry.query })
        assertEquals(original.map { it.durationSeconds },plan.rows.map { it.entry.durationSeconds })
    }

    @Test fun allPublishedNeverOpensManualSelector() {
        val plan=SoulseekAutoReviewPlan.from(entries(2),listOf(done,done))!!
        assertFalse(plan.manualAllowed)
        assertTrue(plan.manualEntries.isEmpty())
        assertEquals(2,plan.published)
    }

    @Test fun ambiguousSingleCanBeOpenedForManualReview() {
        val original=entries(1)
        val plan=SoulseekAutoReviewPlan.from(original,listOf(review))!!
        assertTrue(plan.manualAllowed)
        assertEquals(original,plan.manualEntries)
    }

    @Test fun uncertainCommitBlocksTheWholeReleaseIncludingOtherManualSlots() {
        val plan=SoulseekAutoReviewPlan.from(
            entries(4),listOf(done,review,uncertain,notStarted))!!
        assertEquals(1,plan.needsReconciliation)
        assertFalse(plan.manualAllowed)
        assertTrue(plan.manualEntries.isEmpty())
        assertEquals(1,plan.published)
        assertEquals(1,plan.needsManualReview)
    }

    @Test fun interruptedOrUnstartedWorkCannotBeResumedAsAutomaticFailure() {
        val plan=SoulseekAutoReviewPlan.from(
            entries(4),listOf(done,review,cancelled,notStarted))!!
        assertFalse(plan.manualAllowed)
        assertTrue(plan.manualEntries.isEmpty())
        assertEquals(1,plan.cancelled)
        assertEquals(1,plan.notStarted)
    }

    @Test fun truncatedOrMalformedResultFailsClosedWithoutGuessingSlotIdentity() {
        assertNull(SoulseekAutoReviewPlan.from(entries(2),listOf(review)))
        assertNull(SoulseekAutoReviewPlan.from(entries(2),listOf(review,done,done)))
        assertNull(SoulseekAutoReviewPlan.from(emptyList(),emptyList()))
        assertNull(SoulseekAutoReviewPlan.from(entries(65),List(65){review}))
        assertNull(SoulseekAutoReviewPlan.from(
            listOf(entries(1).first().copy(query="\u0000")),listOf(review)))
    }

    @Test fun largeBatchRetainsEverySlotAndReturnsOnlyUnresolved() {
        val original=entries(64)
        val status=List(64) { index -> if(index%2==0)done else review }
        val plan=SoulseekAutoReviewPlan.from(original,status)!!
        assertTrue(plan.manualAllowed)
        assertEquals(32,plan.manualEntries.size)
        assertEquals((1..63 step 2).toList(),plan.manualIndices)
        assertEquals("SoulseekAutoReviewPlan(redacted)",plan.toString())
    }
}
