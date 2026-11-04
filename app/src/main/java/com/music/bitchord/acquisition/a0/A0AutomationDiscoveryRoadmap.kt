package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * Read-only planning for a future, explicitly opted-in release acquisition.
 *
 * The search for one album track may expose siblings in a peer's folder.
 * Search only slots not yet provisionally covered, never re-query the same
 * canonical slot, and never infer final file identity from peer metadata.
 *
 * No networking, credential, P/F, download, Android storage or mutable
 * preference is reachable from this class. The FINAL per-slot preflight must
 * be re-run on combined observations before a future auto mode can act.
 */
internal object A0AutomationDiscoveryRoadmap {
    enum class State { SEARCH_MORE, PROVISIONAL_COVERAGE, MANUAL_REVIEW, STOPPED }

    class Plan(
        val state:State,
        val requestedSlots:Int,
        val provisionallyCovered:Int,
        val manualReviewSlots:Int,
        val nextSearchSlotIndex:Int?,
    ) {
        // A roadmap is not a transfer or network capability.
        val canSearch:Boolean get()=false
        val canTransfer:Boolean get()=false
        override fun toString()="AutomationDiscoveryRoadmap(aggregates-only)"
    }

    fun preview(
        entries:List<SoulseekBatchPlan.Entry>,
        alreadySearchedSlots:Set<String>,
        observations:List<A0ReleaseDiscoveryPlanner.Preview>,
        cancelled:Boolean=false,
    ):Plan {
        if(entries.size !in 1..SoulseekBatchPlan.MAX_TRACKS ||
            observations.size>SoulseekBatchPlan.MAX_TRACKS ||
            entries.any {it.trackId.isBlank() || it.trackId.length>180 ||
                it.query.isBlank()} ||
            entries.map { it.trackId }.distinct().size!=entries.size ||
            alreadySearchedSlots.any { it !in entries.map { entry -> entry.trackId } }) {
            return Plan(State.MANUAL_REVIEW,entries.size,0,entries.size,null)
        }
        if(cancelled)return Plan(State.STOPPED,entries.size,0,0,null)
        val entryIds=entries.map { it.trackId }.toSet()
        if(observations.any { snapshot ->
                snapshot.trackDecisions.map { it.slotId }.toSet()!=entryIds ||
                    snapshot.trackDecisions.size!=entries.size
            })return Plan(State.MANUAL_REVIEW,entries.size,0,entries.size,null)

        // A second SUGGESTED outcome may concern another claimed edition.
        // As summaries intentionally omit claim identities, two independent
        // positive snapshots are NOT proof of equivalence. Fail closed until
        // the future multi-search coordinator re-screens the union of offers.
        val covered=entries.mapIndexedNotNull { index,entry ->
            val statuses=observations.mapNotNull { snapshot ->
                snapshot.trackDecisions.firstOrNull {
                    it.slotId==entry.trackId
                }?.status
            }
            if(statuses.count { it==A0ReleaseDiscoveryPlanner.Status.SUGGESTED }==1 &&
                A0ReleaseDiscoveryPlanner.Status.AMBIGUOUS !in statuses)index
            else null
        }.toSet()
        val blocked=entries.indices.filter { index ->
            val statuses=observations.mapNotNull { snapshot ->
                snapshot.trackDecisions.firstOrNull {
                    it.slotId==entries[index].trackId
                }?.status
            }
            A0ReleaseDiscoveryPlanner.Status.AMBIGUOUS in statuses ||
                statuses.count { it==A0ReleaseDiscoveryPlanner.Status.SUGGESTED }>1
        }.toSet()
        val next=entries.indices.firstOrNull { index ->
            index !in covered && index !in blocked &&
                entries[index].trackId !in alreadySearchedSlots
        }
        val review=entries.indices.count { index ->
            index !in covered && (index in blocked ||
                entries[index].trackId in alreadySearchedSlots)
        }
        val state=when {
            next!=null -> State.SEARCH_MORE
            review>0 -> State.MANUAL_REVIEW
            else -> State.PROVISIONAL_COVERAGE
        }
        return Plan(state,entries.size,covered.size,review,next)
    }
}
