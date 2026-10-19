package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * Cold-start gate for last-played *local* media. A definitely deleted local item
 * must never be published to Media3 / MiniPlayer on process restart.
 *
 * RED scaffold: deliberately returns the unchanged snapshot until regression
 * tests demonstrate the documented stale-restoration bug.
 */
internal object DeletedLocalMediaRestore {
    fun filter(
        snapshot: LastPlayed.Snapshot,
        isDeletedLocal: (Song) -> Boolean,
    ): LastPlayed.Snapshot? = snapshot
}
