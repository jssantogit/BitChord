package com.music.bitchord.download

import com.music.bitchord.acquisition.a0.A0AutoBatchExecutor
import org.junit.Assert.*
import org.junit.Test

class SoulseekAutoReviewSupersessionTest {
    private fun entry(id:String,query:String)=SoulseekBatchPlan.Entry(id,query,"Album",180)
    private val a=entry("a","Artist - A")
    private val b=entry("b","Artist - B")
    private val c=entry("c","Artist - C")
    private fun review(entry:SoulseekBatchPlan.Entry)=SoulseekAutoReviewPlan.from(
        listOf(entry),listOf(A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW))!!

    @Test fun retryHidesOnlyStaleOverlappingManualReports() {
        val old=mapOf("old-a" to review(a),"unrelated-b" to review(b))
        val replacement=SoulseekAutoReviewSupersession.suppress(old,listOf(a))
        assertEquals(setOf("unrelated-b"),replacement.current.keys)
        assertEquals(setOf("old-a"),replacement.displaced.keys)
        assertEquals(old,SoulseekAutoReviewSupersession.restore(
            replacement.current,replacement.displaced))
    }

    @Test fun failedServiceStartDoesNotOverwriteNewerReport() {
        val old=mapOf("old-a" to review(a))
        val hidden=SoulseekAutoReviewSupersession.suppress(old,listOf(a))
        val newReport=review(c)
        val current=hidden.current + ("old-a" to newReport)
        val recovered=SoulseekAutoReviewSupersession.restore(
            current,hidden.displaced)
        assertSame(newReport,recovered["old-a"])
    }

    @Test fun disjointRetryNeverRemovesExistingReview() {
        val old=mapOf("old-b" to review(b))
        val retained=SoulseekAutoReviewSupersession.suppress(old,listOf(a))
        assertEquals(old,retained.current)
        assertTrue(retained.displaced.isEmpty())
    }
}
