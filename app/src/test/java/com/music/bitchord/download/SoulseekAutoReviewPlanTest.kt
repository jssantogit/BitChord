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

    @Test fun confirmedManualPublicationUpdatesOnlyTheExactOriginalSlot() {
        val original=SoulseekAutoReviewPlan.from(
            entries(4),listOf(done,review,done,review))!!
        val afterThird=original.withManualPublication(3,"ILLIT - Track 4")!!
        assertEquals(listOf(1),afterThird.manualIndices)
        assertEquals(3,afterThird.published)
        assertTrue(afterThird.manualAllowed)
        assertNull(afterThird.withManualPublication(3,"ILLIT - Track 4"))
        assertNull(afterThird.withManualPublication(1,"wrong search term"))
        assertNull(afterThird.withManualPublication(0,"ILLIT - Track 1"))
        val complete=afterThird.withManualPublication(1,"ILLIT - Track 2")!!
        assertEquals(4,complete.published)
        assertFalse(complete.manualAllowed)
        assertTrue(complete.manualEntries.isEmpty())
        assertEquals(2,original.needsManualReview) // immutable snapshot
    }

    @Test fun reconciliationOrInterruptionCannotBeAcknowledgedAsManualSuccess() {
        for(status in listOf(uncertain,cancelled,notStarted)) {
            val plan=SoulseekAutoReviewPlan.from(
                entries(2),listOf(review,status))!!
            assertNull(plan.withManualPublication(0,"ILLIT - Track 1"))
            assertNull(plan.withManualPublication(1,"ILLIT - Track 2"))
        }
    }

    @Test fun restoredOrTamperedActivityPayloadCannotStealTheOriginalReviewLease() {
        val all=entries(4)
        val plan=SoulseekAutoReviewPlan.from(
            all,listOf(done,review,done,review))!!
        val selected=plan.manualEntries.map { it.copy(trackId="synthetic") }
        assertTrue(plan.matchesManualPayload(listOf(1,3),selected))
        assertFalse(plan.matchesManualPayload(listOf(3,1),selected))
        assertFalse(plan.matchesManualPayload(listOf(1,2),selected))
        assertFalse(plan.matchesManualPayload(listOf(1,3),
            selected.mapIndexed { i,entry ->
                if(i==1)entry.copy(durationSeconds=1810) else entry
            }))
        assertFalse(plan.matchesManualPayload(listOf(1,3),
            selected.mapIndexed { i,entry ->
                if(i==0)entry.copy(query="different source") else entry
            }))
        assertFalse(plan.matchesManualPayload(listOf(1),selected.take(1)))
    }

    @Test fun newExplicitRetryInvalidatesStaleManualReviewsForEquivalentSlot() {
        val old=SoulseekAutoReviewPlan.from(entries(2),
            listOf(review,review))!!
        assertTrue(old.overlapsAny(listOf(entries(2)[0])))
        assertTrue(old.overlapsAny(listOf(
            entries(2)[0].copy(trackId="different-provider-id",
                query="  illit  - track 1 ",album=" SUPER REAL ME "))))
        assertFalse(old.overlapsAny(listOf(
            SoulseekBatchPlan.Entry("unrelated","Artist - Another Song",
                "Other Release",180))))
    }

    @Test fun reviewCandidatesAreSessionBoundAndCannotAttachToPublishedSlots() {
        val all=entries(3)
        val one=sampleChoice("peer-one","Track 2")
        val two=sampleChoice("peer-two","Track 2")
        val plan=SoulseekAutoReviewPlan.from(
            all,listOf(done,review,review),
            retainedCandidates=mapOf(0 to listOf(one),1 to listOf(one,two)))!!
        assertTrue(plan.hasRetainedCandidates)
        assertTrue(plan.candidatesForManual(0).isEmpty())
        assertEquals(listOf(one,two),plan.candidatesForManual(1))
        assertTrue(plan.candidatesForManual(2).isEmpty())
        assertTrue(plan.candidatesForManual(999).isEmpty())
        assertFalse(plan.toString().contains("peer-one"))
        assertFalse(plan.rows[1].toString().contains("peer-one"))
        val updated=plan.withManualPublication(1,all[1].query)!!
        assertTrue(updated.candidatesForManual(1).isEmpty())
        assertTrue(updated.candidatesForManual(2).isEmpty())
    }

    private fun sampleChoice(peer:String,title:String):
        com.music.bitchord.acquisition.a0.A0ObservedCandidateHandoff.Choice {
        val quality=com.music.bitchord.acquisition.a0.A0SoulseekQualityProfiles
            .snapshot(com.music.bitchord.data.settings.DownloadQualityProfile.HIGH_QUALITY)
        val handoff=com.music.bitchord.acquisition.a0.A0ObservedCandidateHandoff(
            java.net.Socket(),"local",177,true,
            searchPhrase="ILLIT - $title",requestedAlbum="Super Real Me",
            expectedDurationSeconds=181,qualityProfile=quality,
            collectReleaseShadow=true)
        handoff.offerParsed(
            com.music.bitchord.acquisition.a0.A0SoulseekSearchResponse(
                peer,177,listOf(
                    com.music.bitchord.acquisition.a0.A0SoulseekSearchFile(
                        "Music\\\\ILLIT\\\\Super Real Me\\\\ILLIT - $title.mp3",
                        7_240_000L,"mp3",mapOf(0 to 320,1 to 181))
                ),emptyList(),true,128_000,0),peer)
        return handoff.releaseShadowChoices().single()
    }

    @Test fun malformedRetainedReviewCandidateMapFailsClosed() {
        val all=entries(2)
        assertNull(SoulseekAutoReviewPlan.from(
            all,listOf(review,review),
            retainedCandidates=mapOf(4 to emptyList())))
        assertNull(SoulseekAutoReviewPlan.from(
            all,listOf(review,review),
            retainedCandidates=mapOf(0 to List(17){sampleChoice("peer-$it","Track 1")})))
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
