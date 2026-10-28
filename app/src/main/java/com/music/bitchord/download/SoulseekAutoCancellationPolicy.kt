package com.music.bitchord.download

/**
 * A queued request has made no P/F attempt and may disappear on cancellation.
 * A claimed auto album could already have A3-published siblings: keep a
 * visible verify-library warning, never imply physical rollback.
 * Legacy downloader cancellation keeps its existing UI contract.
 */
internal object SoulseekAutoCancellationPolicy {
    fun retainWarning(isAutomatic:Boolean,wasClaimedByWorker:Boolean):Boolean =
        isAutomatic && wasClaimedByWorker

    /**
     * Cancelling an auto job still present in pending[] proves takeNext()
     * never entered its A3 worker. Under Downloads.lock, this permits
     * releasing only the process-local admission guard. No persistent A3
     * reservation exists; cancellation after claim never qualifies.
     */
    fun canReleaseQueuedIdentity(
        isAutomatic:Boolean,
        wasQueued:Boolean,
        wasClaimedByWorker:Boolean,
    ):Boolean=isAutomatic && wasQueued && !wasClaimedByWorker

    /** startForegroundService failure is not evidence that no other already
     * running Service took the request out of pending[] concurrently.
     */
    fun canRollbackRejectedServiceStart(
        stillPending:Boolean,
        alreadyClaimed:Boolean,
    ):Boolean=stillPending && !alreadyClaimed
}
