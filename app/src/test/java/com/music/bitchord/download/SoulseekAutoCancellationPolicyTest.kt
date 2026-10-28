package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

/** Cancelling a running batch must not hide already-published siblings. */
class SoulseekAutoCancellationPolicyTest {
    @Test fun runningAutomaticAlbumNeedsVisibleLibraryReconciliation() {
        assertTrue(SoulseekAutoCancellationPolicy.retainWarning(
            isAutomatic=true,wasClaimedByWorker=true))
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
