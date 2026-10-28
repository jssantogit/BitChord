package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * Future opt-in coordinator: one bounded DISCOVERY round per needed slot,
 * then one serial TRANSFER pass through A0AutoBatchExecutor. Results from
 * searching one album track can cover siblings; never resubmit their query.
 *
 * SearchPort is an exclusive login/search transport that MUST close all
 * readers and P sockets before returning. The transfer port owns a different
 * fresh authenticated session per attempt. This class does not itself open
 * sockets or register a production DownloadService entrypoint.
 */
internal class A0AutoDiscoveryCoordinator(
    private val discovery:SearchPort,
    private val batchExecutor:A0AutoBatchExecutor,
) {
    interface SearchPort {
        fun search(
            entry:SoulseekBatchPlan.Entry,
            releaseSlots:List<SoulseekBatchPlan.Entry>,
            isCancelled:()->Boolean,
        ):SearchResult
        fun close()
    }

    sealed interface SearchResult {
        class Found(val choices:List<A0ObservedCandidateHandoff.Choice>):SearchResult
        data object NoReply:SearchResult
        /** Auth/account/protocol failure: do NOT automatically reconnect. */
        data object Stop:SearchResult
    }

    class Report(
        val searches:Int,
        val batch:A0AutoBatchExecutor.Report,
    ) {
        override fun toString()=
            "A0AutoDiscoveryReport(searches=$searches,published=${batch.published})"
    }

    private fun refused(count:Int,searches:Int=0)=Report(
        searches,A0AutoBatchExecutor.Report(
            List(count){A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW},0))

    private fun revoked(count:Int,searches:Int)=Report(
        searches,A0AutoBatchExecutor.Report(
            List(count){if(it==0) A0AutoBatchExecutor.TrackStatus.CANCELLED
                else A0AutoBatchExecutor.TrackStatus.NOT_STARTED},0))

    fun run(
        entries:List<SoulseekBatchPlan.Entry>,
        quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        userRequested:Boolean,
        optedIn:Boolean,
        isCancelled:()->Boolean,
        stillAuthorized:()->Boolean,
    ):Report {
        val count=entries.size
        val valid=count in 1..SoulseekBatchPlan.MAX_TRACKS &&
            entries.map {it.trackId}.distinct().size==count &&
            entries.all {it.trackId.isNotBlank() && it.trackId.length<=180 &&
                it.query.isNotBlank()}
        if(!valid || !userRequested || !optedIn)return refused(count)
        fun shouldStop()=isCancelled() || !stillAuthorized() ||
            Thread.currentThread().isInterrupted
        var searches=0
        if(shouldStop())return revoked(count,searches)
        val pooled=A0MultiSearchCandidateEvidence(entries,quality)
        try {
            // Bounded by 64 canonical slots; one query per still-uncovered
            // track and zero duplicate searches even if peers send noise.
            for(index in entries.indices) {
                if(shouldStop())return revoked(count,searches)
                val proposals=pooled.selectionPreview()
                if(proposals.getOrNull(index)?.status==
                    A0AutoCandidateSelectionPolicy.Status.PROVISIONAL)
                    continue
                searches++
                val result=try {
                    discovery.search(entries[index],entries,::shouldStop)
                }catch(_:Exception) {SearchResult.Stop}
                if(shouldStop())return revoked(count,searches)
                when(result) {
                    is SearchResult.Found -> pooled.add(result.choices)
                    SearchResult.NoReply -> Unit
                    SearchResult.Stop -> return refused(count,searches)
                }
            }
            if(shouldStop())return revoked(count,searches)
            pooled.stop()
            return Report(searches,batchExecutor.runFromEvidence(
                entries,pooled,quality,
                userRequested=true,optedIn=true,
                isCancelled=isCancelled,stillAuthorized=stillAuthorized))
        }finally{
            // Closing discovery cannot interrupt separate P/F sessions: those
            // are created only after the collector is sealed.
            try {discovery.close()}catch(_:Exception) {}
        }
    }
}
