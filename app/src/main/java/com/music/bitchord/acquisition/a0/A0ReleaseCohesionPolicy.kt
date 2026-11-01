package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan
import java.util.Locale

/**
 * Release-coherent tie breaker for AMBIGUOUS_EDITION only.
 *
 * One peer's exact release directory can corroborate that it offers multiple
 * independently strong canonical slots. This does NOT authenticate masters.
 * A single, unusually complete source may receive a first P/F attempt only
 * when other competing sources cannot substantiate similar album coverage.
 *
 * Never grants transport authority by itself: the normal per-slot candidate
 * policy, A3 reservation and complete post-transfer technical gate still run.
 */
internal object A0ReleaseCohesionPolicy {
    enum class Reason {
        UNIQUE_RELEASE_COHORT, NO_CANONICAL_RELEASE, INCOMPLETE_SAMPLE,
        INSUFFICIENT_COVERAGE, TIED_COHORTS, NO_ELIGIBLE_COHORT,
    }
    class Assessment(
        val reason:Reason,
        val coverage:Int=0,
        val runnerUpCoverage:Int=0,
        val cohorts:Int=0,
        /** Internal ephemeral handles, never serialized or logged. */
        val choices:List<A0ObservedCandidateHandoff.Choice> = emptyList(),
    ) {
        override fun toString()=
            "ReleaseCohesion(reason=$reason,coverage=$coverage,runnerUp=$runnerUpCoverage)"
    }
    private data class Cohort(val peer:String,val directory:String)
    private fun cohort(choice:A0ObservedCandidateHandoff.Choice):Cohort {
        // Compare complete directory strings, not just a folder basename:
        // "Artist/A/Album" and "Artist/B/Album" are different editions.
        val directory=choice.remotePath.replace('\\','/')
            .substringBeforeLast('/',"")
        return Cohort(choice.peerUsername,directory)
    }

    fun evaluate(
        entries:List<SoulseekBatchPlan.Entry>,
        slot:Int,
        observedBySlot:List<List<A0ObservedCandidateHandoff.Choice>>,
        truncatedSlots:Set<Int>,
        publishedSources:Set<Pair<String,String>>,
        quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
    ):Assessment {
        if(entries.size !in 3..SoulseekBatchPlan.MAX_TRACKS ||
            slot !in entries.indices || observedBySlot.size!=entries.size ||
            entries.map { it.trackId }.distinct().size!=entries.size)
            return Assessment(Reason.NO_CANONICAL_RELEASE)
        val album=entries.first().album?.trim()?.lowercase(Locale.ROOT)
        if(album.isNullOrEmpty() || entries.any {
                it.album?.trim()?.lowercase(Locale.ROOT)!=album ||
                    it.durationSeconds==null
            })return Assessment(Reason.NO_CANONICAL_RELEASE)
        if(truncatedSlots.isNotEmpty() ||
            observedBySlot.any { it.size>512 })
            return Assessment(Reason.INCOMPLETE_SAMPLE)
        val unspent=observedBySlot.map { choices ->
            choices.filterNot {
                (it.peerUsername to it.remotePath) in publishedSources
            }
        }
        val current=unspent[slot]
        if(current.isEmpty())return Assessment(Reason.NO_ELIGIBLE_COHORT)
        // A cohort cannot win on a single guessed query. Every credited
        // sibling MUST pass the unchanged ranking, album, quality, duration,
        // source readiness and contradictory-claim checks independently.
        val bySource=current.groupBy(::cohort)
        data class Candidate(
            val source:Cohort,
            val covered:Int,
            val currentChoices:List<A0ObservedCandidateHandoff.Choice>,
        )
        val candidates=bySource.mapNotNull { (source,offers) ->
            if(A0AutoCandidateSelectionPolicy.preview(
                    entries[slot],offers,quality).status!=
                A0AutoCandidateSelectionPolicy.Status.PROVISIONAL)
                return@mapNotNull null
            var covered=0
            val physicalPaths=HashSet<Pair<String,String>>()
            for(i in entries.indices) {
                val siblings=unspent[i].filter {cohort(it)==source}
                if(siblings.isEmpty())continue
                if(A0AutoCandidateSelectionPolicy.preview(
                        entries[i],siblings,quality).status!=
                    A0AutoCandidateSelectionPolicy.Status.PROVISIONAL)
                    continue
                // One physical file cannot testify to two album tracks.
                val path=siblings.first().peerUsername to siblings.first().remotePath
                if(!physicalPaths.add(path))continue
                covered++
            }
            Candidate(source,covered,offers)
        }.sortedWith(compareByDescending<Candidate> { it.covered }
            .thenBy { it.source.directory })
        if(candidates.isEmpty())return Assessment(Reason.NO_ELIGIBLE_COHORT)
        val best=candidates.first()
        val runner=candidates.getOrNull(1)?.covered ?: 0
        val total=entries.size
        // At least 75% of the release, including the requested track, and
        // a margin of TWO independently eligible sibling tracks. Another
        // equally complete peer stays review-only, regardless of bitrates.
        if(best.covered<3 || best.covered*4<total*3)
            return Assessment(Reason.INSUFFICIENT_COVERAGE,
                best.covered,runner,candidates.size)
        if(best.covered-runner<2)
            return Assessment(Reason.TIED_COHORTS,
                best.covered,runner,candidates.size)
        return Assessment(Reason.UNIQUE_RELEASE_COHORT,
            best.covered,runner,candidates.size,best.currentChoices)
    }
}
