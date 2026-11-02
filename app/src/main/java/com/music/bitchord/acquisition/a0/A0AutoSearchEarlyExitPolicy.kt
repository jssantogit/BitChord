package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * A read-only optimization for a sufficiently populated album search.
 * Never stops a single-track search, never accepts a clipped/contradictory
 * sample, and does not grant permission to download.
 */
internal object A0AutoSearchEarlyExitPolicy {
    fun canFinish(
        elapsedMillis:Long,
        release:List<SoulseekBatchPlan.Entry>,
        quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        shadow:A0ObservedCandidateHandoff.ReleaseShadowSnapshot,
    ):Boolean {
        if(elapsedMillis<8000 || release.size !in 2..SoulseekBatchPlan.MAX_TRACKS)
            return false
        if(shadow.choices.isEmpty() || shadow.truncatedSlots.isNotEmpty() ||
            shadow.shadowDroppedAtGlobalLimit>0 ||
            shadow.shadowDroppedAtPeerLimit>0 ||
            shadow.shadowEvicted>0)return false
        val pooled=A0MultiSearchCandidateEvidence(release,quality)
        return try {
            pooled.add(shadow.choices)
            release.indices.all { index ->
                val (choices,truncated)=pooled.slotEvidence(index) ?: return false
                !truncated && A0AutoCandidateSelectionPolicy.preview(
                    release[index],choices,quality,sampleTruncated=false
                ).status==A0AutoCandidateSelectionPolicy.Status.PROVISIONAL
            }
        }finally {pooled.stop()}
    }
}
