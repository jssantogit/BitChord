package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * One user-requested release, zero parallel P/F operations.
 *
 * Reuses A0AutoAcquisitionExecutor for a single track or 2..64 tracks;
 * already-discovered sibling offers are passed per canonical slot. Missing
 * or ambiguous slots are left for manual review without blocking successfully
 * published siblings. The guarded devDebug service adapter supplies a fresh, exclusive
 * authenticated transport through the Port factory per slot.
 *
 * No discovery or network is started by this class directly. It is
 * composed by the opt-in devDebug foreground queue, never production.
 */
internal class A0AutoBatchExecutor(
    private val portForTrack:(SoulseekBatchPlan.Entry)->A0AutoAcquisitionExecutor.Port,
) {
    enum class TrackStatus { PUBLISHED, MANUAL_REVIEW, CANCELLED, RECONCILE, NOT_STARTED }
    class Report(
        val results:List<TrackStatus>,
        val attempts:Int,
        /** Physical peer paths actually published, used only within one album. */
        val publishedSourceKeys:Set<Pair<String,String>> = emptySet(),
    ) {
        val published:Int get()=results.count { it==TrackStatus.PUBLISHED }
        val manualReview:Int get()=results.count { it==TrackStatus.MANUAL_REVIEW }
        val cancelled:Int get()=results.count { it==TrackStatus.CANCELLED }
        val needsReconciliation:Int get()=results.count { it==TrackStatus.RECONCILE }
        val notStarted:Int get()=results.count { it==TrackStatus.NOT_STARTED }
        override fun toString()=
            "A0AutoBatchReport(published=$published,manual=$manualReview,cancelled=$cancelled)"
    }

    /** Read-only bridge from a closed, canonical-matched release collector. */
    fun runFromEvidence(
        entries:List<SoulseekBatchPlan.Entry>,
        evidence:A0MultiSearchCandidateEvidence,
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        userRequested:Boolean,
        optedIn:Boolean,
        isCancelled:()->Boolean,
        stillAuthorized:()->Boolean,
        onSlotResolved:(Int,TrackStatus)->Unit={ _,_ -> },
    ):Report {
        val ready=evidence.executionSnapshot(entries)
            ?: return Report(List(entries.size){TrackStatus.MANUAL_REVIEW},0)
        return run(entries,ready.choicesBySlot,ready.truncatedSlots,profile,
            userRequested,optedIn,isCancelled,stillAuthorized,onSlotResolved)
    }

    fun run(
        entries:List<SoulseekBatchPlan.Entry>,
        observedBySlot:List<List<A0ObservedCandidateHandoff.Choice>>,
        truncatedSlots:Set<Int>,
        profile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        userRequested:Boolean,
        optedIn:Boolean,
        isCancelled:()->Boolean,
        stillAuthorized:()->Boolean,
        onSlotResolved:(Int,TrackStatus)->Unit={ _,_ -> },
    ):Report {
        val count=entries.size
        val valid=count in 1..SoulseekBatchPlan.MAX_TRACKS &&
            entries.map { it.trackId }.distinct().size==count &&
            entries.all { it.trackId.isNotBlank() && it.trackId.length<=180 &&
                it.query.isNotBlank() } &&
            observedBySlot.size==count &&
            truncatedSlots.all { it in entries.indices }
        if(!valid || !userRequested || !optedIn)
            return Report(List(count){TrackStatus.MANUAL_REVIEW},0)
        val status=ArrayList<TrackStatus>(count)
        fun finishSlot(index:Int,value:TrackStatus) {
            status.add(value)
            // Progress delivery must never turn an A3 publication into a
            // failed acquisition; callback is strictly observational.
            try { onSlotResolved(index,value) }catch(_:Exception) {}
        }
        var attempts=0
        // One physical peer path must NEVER be automatically published as two
        // different canonical album slots. Reprises need separate evidence.
        val publishedSources=HashSet<Pair<String,String>>()
        for(index in entries.indices) {
            if(isCancelled() || !stillAuthorized() ||
                Thread.currentThread().isInterrupted) {
                finishSlot(index,TrackStatus.CANCELLED)
                repeat(count-index-1){ status.add(TrackStatus.NOT_STARTED) }
                break
            }
            if(index in truncatedSlots) {
                finishSlot(index,TrackStatus.MANUAL_REVIEW)
                continue
            }
            // Check eligibility before opening any transport, so an unknown
            // release slot never causes even an authenticated login.
            val unspent=observedBySlot[index].filterNot { choice ->
                (choice.peerUsername to choice.remotePath) in publishedSources
            }
            val preview=A0AutoCandidateSelectionPolicy.preview(
                entries[index],unspent,profile)
            if(preview.status!=A0AutoCandidateSelectionPolicy.Status.PROVISIONAL) {
                finishSlot(index,TrackStatus.MANUAL_REVIEW)
                continue
            }
            val result=try {
                A0AutoAcquisitionExecutor(portForTrack(entries[index])).run(
                    entries[index],unspent,profile,
                    userRequested=true,optedIn=true,
                    isCancelled=isCancelled,stillAuthorized=stillAuthorized)
            }catch(_:Exception) {
                null
            }
            if(result!=null)attempts+=result.attempts
            if(result?.status==A0AutoAcquisitionExecutor.Status.PUBLISHED)
                result.selectedSource?.let {
                    publishedSources.add(it.peerUsername to it.remotePath)
                }
            finishSlot(index,when(result?.status) {
                A0AutoAcquisitionExecutor.Status.PUBLISHED -> TrackStatus.PUBLISHED
                A0AutoAcquisitionExecutor.Status.CANCELLED -> TrackStatus.CANCELLED
                A0AutoAcquisitionExecutor.Status.RECONCILE -> TrackStatus.RECONCILE
                else -> TrackStatus.MANUAL_REVIEW
            })
            if(result?.status in setOf(
                A0AutoAcquisitionExecutor.Status.CANCELLED,
                A0AutoAcquisitionExecutor.Status.RECONCILE
            )) {
                repeat(count-index-1){status.add(TrackStatus.NOT_STARTED)}
                break
            }
        }
        return Report(status,attempts,publishedSources.toSet())
    }
}
