package com.music.bitchord.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTrackerTest {
    private fun failed(id: String, asset: String = "asset-$id") =
        PlaybackRecoveryNotice(
            entryId = "entry-$id",
            recordingId = "recording-$id",
            mediaAssetId = asset,
            providerKey = "opensubsonic:home",
            reason = PlaybackRecoveryReason.CONNECTION_OR_SERVER,
        )

    @Test fun `two failed remote items and local playback retain actionable latest failure`() {
        val tracker = PlaybackRecoveryTracker()
        tracker.reportFailure(failed("a"), beforeAudio = true)
        tracker.reportFailure(failed("b"), beforeAudio = true)
        tracker.onAudible("local-c")
        assertEquals("entry-b", tracker.current?.entryId)
        assertEquals(PlaybackRecoveryStatus.WAITING_FOR_USER, tracker.current?.status)
        assertEquals(PlaybackRecoveryReason.CONNECTION_OR_SERVER, tracker.current?.reason)
    }

    @Test fun `reconnection signal does not start or accept a retry without user intent`() {
        val tracker = PlaybackRecoveryTracker()
        val notice = failed("a")
        tracker.reportFailure(notice, beforeAudio = true)
        assertEquals(PlaybackRecoveryStatus.WAITING_FOR_USER, tracker.current?.status)
        // No network-health callback can invoke beginRetry: the only transition
        // to RETRYING is the explicit, identity-bound user action below.
        assertEquals(PlaybackRecoveryRetryResult.STARTED,
            tracker.beginRetry(notice.entryId, notice.recordingId, notice.mediaAssetId, notice.providerKey, present = true))
        assertEquals(PlaybackRecoveryStatus.RETRYING, tracker.current?.status)
    }

    @Test fun `wrong occurrence or changed provider never substitutes a rendition`() {
        val tracker = PlaybackRecoveryTracker()
        val n = failed("a")
        tracker.reportFailure(n, true)
        assertEquals(PlaybackRecoveryRetryResult.STALE_SELECTION,
            tracker.beginRetry("entry-b", n.recordingId, n.mediaAssetId, n.providerKey, true))
        assertEquals(PlaybackRecoveryRetryResult.STALE_SELECTION,
            tracker.beginRetry(n.entryId, n.recordingId, "different-asset", n.providerKey, true))
        assertEquals(PlaybackRecoveryRetryResult.STALE_SELECTION,
            tracker.beginRetry(n.entryId, n.recordingId, n.mediaAssetId, "other-provider", true))
        assertEquals(PlaybackRecoveryStatus.WAITING_FOR_USER, tracker.current?.status)
    }

    @Test fun `item removed before retry cannot be resurrected`() {
        val tracker = PlaybackRecoveryTracker()
        val n = failed("a")
        tracker.reportFailure(n, true)
        assertEquals(PlaybackRecoveryRetryResult.SOURCE_REMOVED,
            tracker.beginRetry(n.entryId, n.recordingId, n.mediaAssetId, n.providerKey, false))
        assertNull(tracker.current)
    }

    @Test fun `duplicate retry taps do not make a retry loop`() {
        val tracker = PlaybackRecoveryTracker()
        val n = failed("a")
        tracker.reportFailure(n, true)
        assertEquals(PlaybackRecoveryRetryResult.STARTED,
            tracker.beginRetry(n.entryId, n.recordingId, n.mediaAssetId, n.providerKey, true))
        assertEquals(PlaybackRecoveryRetryResult.ALREADY_RETRYING,
            tracker.beginRetry(n.entryId, n.recordingId, n.mediaAssetId, n.providerKey, true))
        tracker.onRetryFailed(n.entryId)
        assertEquals(PlaybackRecoveryStatus.WAITING_FOR_USER, tracker.current?.status)
    }

    @Test fun `only audio from the original failed queue entry clears the notice`() {
        val tracker = PlaybackRecoveryTracker()
        val n = failed("a")
        tracker.reportFailure(n, true)
        tracker.onAudible("a-local-different-entry")
        assertNotNull(tracker.current)
        tracker.onAudible(n.entryId)
        assertNull(tracker.current)
    }

    @Test fun `post-audio and unrelated failures cannot overwrite active recovery`() {
        val tracker = PlaybackRecoveryTracker()
        val n = failed("a")
        tracker.reportFailure(n, true)
        tracker.reportFailure(failed("b"), beforeAudio = false)
        assertEquals(n.entryId, tracker.current?.entryId)
    }

    @Test fun `late queue replacement invalidates old retry and background notice`() {
        val tracker = PlaybackRecoveryTracker()
        val n = failed("a")
        tracker.reportFailure(n, true)
        tracker.reconcileQueue(setOf("entry-b", "entry-c"))
        assertNull(tracker.current)
        assertEquals(PlaybackRecoveryRetryResult.STALE_SELECTION,
            tracker.beginRetry(n.entryId, n.recordingId, n.mediaAssetId, n.providerKey, true))
    }
}
