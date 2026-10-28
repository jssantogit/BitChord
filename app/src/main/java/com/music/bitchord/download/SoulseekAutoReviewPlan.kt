package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0AutoBatchExecutor

/**
 * An in-memory, read-only view of exactly one completed Soulseek auto attempt.
 *
 * A successful A3 publication is never eligible for another manual transfer.
 * A RECONCILE, CANCELLED or NOT_STARTED state blocks the entire recovery
 * action: the disk/account state must be checked before touching any peer.
 * Peer IDs, remote paths, claims and credentials are NOT retained here.
 */
internal class SoulseekAutoReviewPlan private constructor(
    val rows:List<Row>,
) {
    internal data class Row(
        val entry:SoulseekBatchPlan.Entry,
        val status:A0AutoBatchExecutor.TrackStatus,
    )

    val published:Int = rows.count { it.status==A0AutoBatchExecutor.TrackStatus.PUBLISHED }
    val needsManualReview:Int = rows.count {
        it.status==A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW
    }
    val needsReconciliation:Int = rows.count {
        it.status==A0AutoBatchExecutor.TrackStatus.RECONCILE
    }
    val cancelled:Int = rows.count {
        it.status==A0AutoBatchExecutor.TrackStatus.CANCELLED
    }
    val notStarted:Int = rows.count {
        it.status==A0AutoBatchExecutor.TrackStatus.NOT_STARTED
    }

    val manualAllowed:Boolean = needsManualReview>0 &&
        needsReconciliation==0 && cancelled==0 && notStarted==0

    val manualIndices:List<Int> = if(manualAllowed)
        rows.indices.filter {
            rows[it].status==A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW
        } else emptyList()

    val manualEntries:List<SoulseekBatchPlan.Entry> =
        manualIndices.map { rows[it].entry }

    /** The Activity's non-exported Intent must describe this SAME ordered set
     * of remaining slots. Synthetic batch payload IDs are intentionally ignored.
     */
    fun matchesManualPayload(
        originalIndices:List<Int>,
        proposed:List<SoulseekBatchPlan.Entry>,
    ):Boolean {
        if(!manualAllowed || originalIndices!=manualIndices ||
            proposed.size!=manualEntries.size)return false
        return proposed.zip(manualEntries).all { (candidate,canonical) ->
            candidate.query==canonical.query &&
                candidate.album==canonical.album &&
                candidate.durationSeconds==canonical.durationSeconds
        }
    }

    /**
     * Called only after A3 confirms the manual publication. The caller must
     * carry the ORIGINAL slot index, not a moving index into manualEntries.
     * A duplicate acknowledgement or mismatched target is refused.
     */
    fun withManualPublication(
        originalIndex:Int,
        originalQuery:String,
    ):SoulseekAutoReviewPlan? {
        if(!manualAllowed || originalIndex !in manualIndices ||
            rows[originalIndex].entry.query!=originalQuery)return null
        val changed=rows.mapIndexed { index,row ->
            if(index==originalIndex)
                row.copy(status=A0AutoBatchExecutor.TrackStatus.PUBLISHED)
            else row
        }
        return SoulseekAutoReviewPlan(changed)
    }

    override fun toString():String = "SoulseekAutoReviewPlan(redacted)"

    companion object {
        fun from(
            entries:List<SoulseekBatchPlan.Entry>,
            results:List<A0AutoBatchExecutor.TrackStatus>,
        ):SoulseekAutoReviewPlan? {
            if(results.size!=entries.size ||
                !SoulseekAutoQueueAdmission.allows(
                    entries,userRequested=true,optedIn=true,
                    devPilot=true,downloadsAllowed=true))return null
            return SoulseekAutoReviewPlan(
                entries.zip(results) { entry,status -> Row(entry.copy(),status) }
            )
        }
    }
}
