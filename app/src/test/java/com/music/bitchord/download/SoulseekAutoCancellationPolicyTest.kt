package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

/** Cancelling a running batch must not hide already-published siblings. */
class SoulseekAutoCancellationPolicyTest {
    @Test fun runningAutomaticAlbumNeedsVisibleLibraryReconciliation() {
        assertTrue(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=true,wasClaimedByWorker=true))
    }

    @Test fun queuedNeverClaimedAutomaticRequestMayReleaseOnlyRAMFence() {
        assertTrue(SoulseekAutoCancellationPolicy.canReleaseQueuedIdentity(
            isAutomatic=true,wasQueued=true,wasClaimedByWorker=false))
        for((auto,queued,claimed) in listOf(
            Triple(true,false,false),
            Triple(true,true,true),
            Triple(true,false,true),
            Triple(false,true,false),
            Triple(false,true,true)
        )) {
            assertFalse(SoulseekAutoCancellationPolicy.canReleaseQueuedIdentity(
                isAutomatic=auto,wasQueued=queued,wasClaimedByWorker=claimed))
        }
    }

    @Test fun queuedCancellationCanBeExplicitlyReenqueuedButClaimedCannot() {
        val guard=SoulseekAutoAttemptGuard()
        val slots=listOf(SoulseekBatchPlan.Entry(
            "recording-1","Artist - Track","Album",180))
        assertTrue(guard.reserve(slots))
        if(SoulseekAutoCancellationPolicy.canReleaseQueuedIdentity(
                isAutomatic=true,wasQueued=true,wasClaimedByWorker=false))
            guard.rollback(slots)
        assertTrue(guard.reserve(slots))
        if(SoulseekAutoCancellationPolicy.canReleaseQueuedIdentity(
                isAutomatic=true,wasQueued=false,wasClaimedByWorker=true))
            guard.rollback(slots)
        assertFalse(guard.reserve(slots))
    }

    @Test fun failedForegroundServiceStartMustNotReleaseClaimedOrSettledAutoJob() {
        val gate=SoulseekAutoAttemptGuard()
        val entries=listOf(SoulseekBatchPlan.Entry(
            "source-recording","Artist - Song","Album",180))
        assertTrue(gate.reserve(entries))
        // Another already-running Service could claim this request between
        // queue admission and startForegroundService throwing.
        if(SoulseekAutoCancellationPolicy.canRollbackRejectedServiceStart(
                stillPending=false,alreadyClaimed=true))
            gate.rollback(entries)
        assertFalse(gate.reserve(entries))
        // A request which has already settled is also not owned by the
        // rejected start attempt, so it must not be rolled back here.
        assertFalse(SoulseekAutoCancellationPolicy.canRollbackRejectedServiceStart(
            stillPending=false,alreadyClaimed=false))
        assertTrue(SoulseekAutoCancellationPolicy.canRollbackRejectedServiceStart(
            stillPending=true,alreadyClaimed=false))
        assertFalse(SoulseekAutoCancellationPolicy.canRollbackRejectedServiceStart(
            stillPending=true,alreadyClaimed=true))
    }

    @Test fun pendingAutoAndLegacyCancelsRemainRemovable() {
        assertFalse(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=true,wasClaimedByWorker=false))
        assertFalse(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=false,wasClaimedByWorker=true))
        assertFalse(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=false,wasClaimedByWorker=false))
    }
}
