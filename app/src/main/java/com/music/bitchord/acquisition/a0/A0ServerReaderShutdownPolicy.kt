package com.music.bitchord.acquisition.a0

/**
 * Read errors caused by an explicit user stop are an expected consequence
 * of closing the socket, not evidence of server/protocol instability.
 * Unexpected failures while the session is live remain fail-closed.
 * Pure policy used by the devDebug reader; no account or network access.
 */
internal object A0ServerReaderShutdownPolicy {
    fun shouldReportReadFailure(userStopRequested:Boolean):Boolean =
        !userStopRequested

    fun shouldReportPendingFrameFailure(userStopRequested:Boolean):Boolean =
        !userStopRequested
}
