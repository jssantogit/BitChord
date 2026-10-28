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
}
