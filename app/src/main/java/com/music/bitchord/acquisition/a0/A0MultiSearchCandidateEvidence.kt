package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * A strictly bounded, in-memory portfolio of peer claims across distinct
 * user-requested release-slot searches. NO network, storage or P/F authority.
 *
 * The same response may advertise sibling tracks. Per-slot partitions stop
 * a popular track from hiding a rarer one in a 64-track release. Any loss of
 * evidence through the cap or contradictory metadata FAILS CLOSED to REVIEW.
 * An eventual opt-in engine MUST independently revalidate selected bytes.
 */
internal class A0MultiSearchCandidateEvidence(
    private val entries:List<SoulseekBatchPlan.Entry>,
    private val quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
) {
    private val valid=entries.size in 1..SoulseekBatchPlan.MAX_TRACKS &&
        entries.all { it.trackId.isNotBlank() && it.trackId.length<=180 &&
            it.query.isNotBlank() } &&
        entries.map { it.trackId }.distinct().size==entries.size
    private val limitPerSlot=if(valid) minOf(128,512/entries.size) else 0
    private val titleTokens=if(valid) entries.map {
        A0ReleaseDiscoveryShadowAdapter.titleTokens(it.query)
    } else emptyList()
    private val buckets=if(valid) entries.map {
        LinkedHashMap<Pair<String,String>,A0ObservedCandidateHandoff.Choice>()
    } else emptyList()
    private val clipped=BooleanArray(entries.size)
    private var stopped=false

    data class Snapshot(
        val preview:A0ReleaseDiscoveryPlanner.Preview?,
        val observedPairs:Int,
        val retainedPairs:Int,
        val blockedSlots:Int,
    ) {
        val canTransfer:Boolean get()=false
        val canSearch:Boolean get()=false
        override fun toString()="MultiSearchCandidateEvidence(aggregates-only)"
    }

    @Synchronized
    fun add(choices:List<A0ObservedCandidateHandoff.Choice>) {
        if(!valid || stopped)return
        // A collector is bounded at 512; distrust larger external lists and
        // signal lost coverage rather than silently treating it as complete.
        if(choices.size>512)clipped.fill(true)
        for(choice in choices.take(512)) {
            val tokens=A0ReleaseDiscoveryShadowAdapter.fileTokens(choice.claim.filename)
            for(index in entries.indices) {
                if(titleTokens[index].isEmpty() ||
                    !tokens.containsAll(titleTokens[index]))continue
                val bucket=buckets[index]
                val key=choice.peerUsername to choice.remotePath
                val prior=bucket[key]
                if(prior!=null) {
                    if(!A0CandidateClaimClusters.sameClaim(prior.claim,choice.claim))
                        clipped[index]=true
                } else if(bucket.size<limitPerSlot) {
                    bucket[key]=choice
                } else clipped[index]=true
            }
        }
    }

    @Synchronized
    fun stop() { stopped=true }

    /**
     * A release-wide read-only suggestion for each canonical slot, using
     * ALL retained searches. No single song inherits another song's original
     * preflight confidence. A clipped/contradictory slot is review-only.
     */
    @Synchronized
    fun selectionPreview(
        reliability:A0PeerReliabilityEvidence=A0PeerReliabilityEvidence(),
    ):List<A0AutoCandidateSelectionPolicy.Decision> {
        if(!valid)return emptyList()
        return entries.mapIndexed { index,entry ->
            A0AutoCandidateSelectionPolicy.preview(
                entry,buckets[index].values.toList(),quality,reliability,
                sampleTruncated=clipped[index],
            )
        }
    }

    @Synchronized
    fun snapshot():Snapshot {
        if(!valid)return Snapshot(null,0,0,entries.size)
        val combined=LinkedHashMap<Pair<String,String>,A0ObservedCandidateHandoff.Choice>()
        for(bucket in buckets)for((key,choice) in bucket)combined.putIfAbsent(key,choice)
        val preview=A0ReleaseDiscoveryShadowAdapter.preview(entries,
            combined.values.toList(),quality)
        val blocked=clipped.count { it }
        if(blocked==0)return Snapshot(preview,
            buckets.sumOf { it.size },combined.size,0)
        val decisions=preview.trackDecisions.mapIndexed { index,decision ->
            if(clipped[index])A0ReleaseDiscoveryPlanner.TrackDecision(
                entries[index].trackId,A0ReleaseDiscoveryPlanner.Status.REVIEW,emptyList()
            ) else decision
        }
        val ready=decisions.count {
            it.status==A0ReleaseDiscoveryPlanner.Status.SUGGESTED
        }
        val safe=A0ReleaseDiscoveryPlanner.Preview(
            autoReadyTracks=ready,
            manualReviewTracks=entries.size-ready,
            ambiguousTracks=decisions.count {
                it.status==A0ReleaseDiscoveryPlanner.Status.AMBIGUOUS
            },
            bestFolderCoverage=preview.bestFolderCoverage,
            foldersConsidered=preview.foldersConsidered,
            examinedOffers=preview.examinedOffers,
            trackDecisions=decisions,
            rejections=preview.rejections,
            intake=preview.intake)
        return Snapshot(safe,buckets.sumOf { it.size },combined.size,blocked)
    }
}
