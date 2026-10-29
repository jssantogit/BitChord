package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * One bounded DISCOVERY round per needed slot followed immediately by its
 * serial TRANSFER. The next slot can reuse sibling peer evidence already
 * collected, avoiding redundant searches and album-wide discovery stalls.
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
        class Found(
            val choices:List<A0ObservedCandidateHandoff.Choice>,
            val truncatedSlots:Set<Int> = emptySet(),
        ):SearchResult
        data object NoReply:SearchResult
        /** Auth/account/protocol failure: do NOT automatically reconnect. */
        data object Stop:SearchResult
    }

    class Report(
        val searches:Int,
        val batch:A0AutoBatchExecutor.Report,
        /** A3 atomically proved that NO sibling began P/F. Explicit retry
         * of the same identity is safe; never set this from peer claims.
         */
        val unusedReservationReleased:Boolean=false,
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
        onSlotResolved:(Int,A0AutoBatchExecutor.TrackStatus)->Unit={ _,_ -> },
        onSlotSearching:(Int)->Unit={},
        onCandidateDecision:(Int,A0AutoCandidateSelectionPolicy.Decision)->Unit={ _,_ -> },
        /** Observed, never transfer-authorized; consumer may retain in RAM
         * for one explicit human review after this search closes.
         */
        onSlotEvidence:(Int,List<A0ObservedCandidateHandoff.Choice>)->Unit={ _,_ -> },
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
        val statuses=ArrayList<A0AutoBatchExecutor.TrackStatus>(count)
        val publishedPaths=HashSet<Pair<String,String>>()
        var attempts=0
        fun append(index:Int,status:A0AutoBatchExecutor.TrackStatus) {
            statuses.add(status)
            try {onSlotResolved(index,status)}catch(_:Exception){}
        }
        fun finish():Report=Report(searches,
            A0AutoBatchExecutor.Report(statuses.toList(),attempts,
                publishedPaths.toSet()))
        try {
            // Each search() shuts down its readers and sockets before
            // returning. Publish this slot NOW, rather than searching an
            // entire album first. Peer evidence already includes sibling
            // tracks and can eliminate the next redundant search.
            for(index in entries.indices) {
                if(shouldStop()) {
                    append(index,A0AutoBatchExecutor.TrackStatus.CANCELLED)
                    for(next in index+1 until count)
                        append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                    break
                }
                // Observable per-track state, never network authority.
                try {onSlotSearching(index)}catch(_:Exception){}
                val proposals=pooled.selectionPreview()
                if(proposals.getOrNull(index)?.status!=
                    A0AutoCandidateSelectionPolicy.Status.PROVISIONAL) {
                    searches++
                    val result=try {
                        discovery.search(entries[index],entries,::shouldStop)
                    }catch(_:Exception) { SearchResult.Stop }
                    if(shouldStop()) {
                        append(index,A0AutoBatchExecutor.TrackStatus.CANCELLED)
                        for(next in index+1 until count)
                            append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                        break
                    }
                    when(result) {
                        is SearchResult.Found ->
                            pooled.add(result.choices,result.truncatedSlots)
                        SearchResult.NoReply -> Unit
                        SearchResult.Stop -> {
                            append(index,A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW)
                            for(next in index+1 until count)
                                append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                            break
                        }
                    }
                }
                val (choices,truncated)=checkNotNull(pooled.slotEvidence(index))
                // Do not reuse a physical peer file that has already been
                // published for another canonical sibling in this album.
                val unique=choices.filterNot {
                    (it.peerUsername to it.remotePath) in publishedPaths
                }
                // Preserve peer evidence before attempting automatic P/F;
                // review screens must not force a second identical search.
                // Callback is observational, not a transport command.
                try {onSlotEvidence(index,unique.toList())}catch(_:Exception){}
                val decision=A0AutoCandidateSelectionPolicy.preview(
                    entries[index],unique,quality,sampleTruncated=truncated)
                // Counts and reason only: never expose peer/user/path to logs.
                try { onCandidateDecision(index,decision) }catch(_:Exception){}
                val outcome=batchExecutor.run(
                    entries=listOf(entries[index]),
                    observedBySlot=listOf(unique),
                    truncatedSlots=if(truncated)setOf(0) else emptySet(),
                    profile=quality,userRequested=true,optedIn=true,
                    isCancelled=isCancelled,stillAuthorized=stillAuthorized,
                    onSlotResolved={_,status -> onSlotResolved(index,status)},
                )
                attempts+=outcome.attempts
                publishedPaths.addAll(outcome.publishedSourceKeys)
                val status=outcome.results.singleOrNull()
                    ?: A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW
                // The executor already delivered this slot's progress.
                statuses.add(status)
                if(status in setOf(
                    A0AutoBatchExecutor.TrackStatus.CANCELLED,
                    A0AutoBatchExecutor.TrackStatus.RECONCILE)) {
                    for(next in index+1 until count)
                        append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                    break
                }
            }
            return finish()
        }finally {
            pooled.stop()
            try {discovery.close()}catch(_:Exception){}
        }
    }
}
