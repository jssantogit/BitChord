package com.music.bitchord.playback

import com.music.bitchord.data.model.Song

/**
 * Filters definitely-deleted *local* rows before a LastPlayed cold-start
 * snapshot reaches Media3/MiniPlayer. Unknown availability is not deletion.
 * No source re-selection, canonical identity rewrite or playback event occurs.
 */
internal object DeletedLocalMediaRestore {
    fun filter(
        snapshot: LastPlayed.Snapshot,
        isDeletedLocal: (Song) -> Boolean,
    ): LastPlayed.Snapshot? {
        if (snapshot.songs.isEmpty()) return null
        val current = snapshot.index.coerceIn(snapshot.songs.indices)
        val survivors = snapshot.songs.withIndex().filterNot { (_, song) ->
            song.isLocalRestoreCandidate() && isDeletedLocal(song)
        }
        if (survivors.isEmpty()) return null

        // Prefer an already queued successor. If the tail was removed, use
        // the nearest remaining predecessor; never invent a replacement song.
        val next = survivors.indexOfFirst { it.index >= current }
        val restoredIndex = if (next >= 0) next else survivors.lastIndex
        val sameSelection = survivors[restoredIndex].index == current
        return LastPlayed.Snapshot(
            songs = survivors.map { it.value },
            index = restoredIndex,
            positionMs = if (sameSelection) snapshot.positionMs else 0L,
        )
    }

    private fun Song.isLocalRestoreCandidate(): Boolean {
        val selectedUri = localUri
        if (selectedUri != null) {
            return selectedUri.startsWith("content://media/") || selectedUri.startsWith("file://")
        }
        return videoId.startsWith("content://media/") ||
            videoId.startsWith("file://") ||
            !localPath.isNullOrBlank()
    }
}
