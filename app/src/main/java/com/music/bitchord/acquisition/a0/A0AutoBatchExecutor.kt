package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * One user-requested release, zero parallel P/F operations.
 *
 * Reuses A0AutoAcquisitionExecutor for a single track or 2..64 tracks;
 * already-discovered sibling offers are passed per canonical slot. Missing
 * or ambiguous slots are left for manual review without blocking successfully
 * published siblings. A future Service adapter MUST supply a fresh, exclusive
 * authenticated transport through the Port factory per slot.
 *
 * No discovery or network is started by this class. It is not registered with
 * the running Android service yet, so AUTO remains OFF in production.
 */
internal class A0AutoBatchExecutor(
    private val portForTrack:(SoulseekBatchPlan.Entry)->A0AutoAcquisitionExecutor.Port,
) {
    enum class TrackStatus { PUBLISHED, MANUAL_REVIEW, CANCELLED, NOT_STARTED }
    class Report(
        val results:List<TrackStatus>,
        val attempts:Int,
    ) {
        val published:Int get()=results.count { it==TrackStatus.PUBLISHED }
        val manualReview:Int get()=results.count { it==TrackStatus.MANUAL_REVIEW }
        val cancelled:Int get()=results.count { it==TrackStatus.CANCELLED }
        val notStarted:Int get()=results.count { it==TrackStatus.NOT_STARTED }
        override fun toString()=
            "A0AutoBatchReport(published=$published,manual=$manualReview,cancelled=$cancelled)"
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
        var attempts=0
        for(index in entries.indices) {
            if(isCancelled() || !stillAuthorized() ||
                Thread.currentThread().isInterrupted) {
                status.add(TrackStatus.CANCELLED)
                repeat(count-index-1){ status.add(TrackStatus.NOT_STARTED) }
                break
            }
            if(index in truncatedSlots) {
                status.add(TrackStatus.MANUAL_REVIEW)
                continue
            }
            // Check eligibility before opening any transport, so an unknown
            // release slot never causes even an authenticated login.
            val preview=A0AutoCandidateSelectionPolicy.preview(
                entries[index],observedBySlot[index],profile)
            if(preview.status!=A0AutoCandidateSelectionPolicy.Status.PROVISIONAL) {
                status.add(TrackStatus.MANUAL_REVIEW)
                continue
            }
            val result=try {
                A0AutoAcquisitionExecutor(portForTrack(entries[index])).run(
                    entries[index],observedBySlot[index],profile,
                    userRequested=true,optedIn=true,
                    isCancelled=isCancelled,stillAuthorized=stillAuthorized)
            }catch(_:Exception) {
                null
            }
            if(result!=null)attempts+=result.attempts
            status.add(when(result?.status) {
                A0AutoAcquisitionExecutor.Status.PUBLISHED -> TrackStatus.PUBLISHED
                A0AutoAcquisitionExecutor.Status.CANCELLED -> TrackStatus.CANCELLED
                else -> TrackStatus.MANUAL_REVIEW
            })
            if(result?.status==A0AutoAcquisitionExecutor.Status.CANCELLED) {
                repeat(count-index-1){status.add(TrackStatus.NOT_STARTED)}
                break
            }
        }
        return Report(status,attempts)
    }
}
