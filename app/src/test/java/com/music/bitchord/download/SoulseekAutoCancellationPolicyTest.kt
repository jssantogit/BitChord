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

    @Test fun pendingAutoAndLegacyCancelsRemainRemovable() {
        assertFalse(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=true,wasClaimedByWorker=false))
        assertFalse(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=false,wasClaimedByWorker=true))
        assertFalse(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=false,wasClaimedByWorker=false))
    }
}
