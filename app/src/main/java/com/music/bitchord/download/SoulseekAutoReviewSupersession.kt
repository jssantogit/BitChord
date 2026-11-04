package com.music.bitchord.download

/**
 * Keep stale manual recovery reports invisible for a fresh explicit request,
 * but never lose them merely because Android refused to start the foreground
 * service. Current reports always win over an older displaced snapshot.
 */
internal object SoulseekAutoReviewSupersession {
    data class Change(
        val current:Map<String,SoulseekAutoReviewPlan>,
        val displaced:Map<String,SoulseekAutoReviewPlan>,
    )

    fun suppress(
        current:Map<String,SoulseekAutoReviewPlan>,
        entries:List<SoulseekBatchPlan.Entry>,
    ):Change {
        val displaced=current.filterValues { it.overlapsAny(entries) }
        return Change(current=current - displaced.keys,displaced=displaced)
    }

    fun restore(
        current:Map<String,SoulseekAutoReviewPlan>,
        displaced:Map<String,SoulseekAutoReviewPlan>,
    ):Map<String,SoulseekAutoReviewPlan> = displaced + current
}
