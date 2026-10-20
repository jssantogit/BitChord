package com.music.bitchord.playback

/** The existing controller still owns the actual two-player settlement. */
internal enum class CrossfadeInterruption {
    ROUTE_CHANGE,
    CONTROLLER_RELEASE,
    MANUAL_SKIP,
    SEEK,
    PLAYER_ERROR,
}

/** Physical ownership change must not leave a second audible ExoPlayer. */
internal object CrossfadeHandoffCancellation {
    fun settleImmediately(reason: CrossfadeInterruption): Boolean = when (reason) {
        CrossfadeInterruption.ROUTE_CHANGE, CrossfadeInterruption.CONTROLLER_RELEASE -> true
        CrossfadeInterruption.MANUAL_SKIP, CrossfadeInterruption.SEEK,
        CrossfadeInterruption.PLAYER_ERROR -> false
    }
}
