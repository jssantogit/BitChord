package com.music.bitchord.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ScopedCaptureOwnershipTest {
    @Test fun manualCaptureIsNeverClaimedByFeature() {
        val state=ScopedCaptureOwnership()
        assertNull(state.currentLease())
        state.manualTransition()
        assertFalse(state.release(null))
    }

    @Test fun scopedCaptureOnlyStopsItsOwnSession() {
        val state=ScopedCaptureOwnership()
        val initial=state.claim()
        assertTrue(state.release(initial))
        assertFalse(state.release(initial))
    }

    @Test fun manualStartInvalidatesAnOlderSoulseekClaim() {
        val state=ScopedCaptureOwnership()
        val old=state.claim()
        state.manualTransition()
        assertFalse(state.release(old))
    }

    @Test fun anotherCaptureClaimCannotBeStoppedByStaleOwner() {
        val state=ScopedCaptureOwnership()
        val old=state.claim()
        val newer=state.claim()
        assertFalse(state.release(old))
        assertTrue(state.release(newer))
    }

    @Test fun manualStopInvalidatesOlderFeatureClaim() {
        val state=ScopedCaptureOwnership()
        val lease=state.claim()
        state.manualTransition()
        assertFalse(state.release(lease))
    }
}
