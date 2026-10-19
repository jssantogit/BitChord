package com.music.bitchord.playback

/**
 * Compatibility gate for a *decoded* bounded snapshot which failed projection
 * during service/Activity recreation.
 *
 * A canonical identity is authoritative even if its selected asset is absent:
 * falling back to LastPlayed can resurrect deleted local media or an obsolete
 * remote rendition. Unmapped legacy-only snapshots keep their old fallback.
 * This decision never prepares or starts Media3 playback.
 */
internal object PlaybackLifecycleRestoreGate {
    fun mayFallBackAfterFailedProjection(state: BoundedContextPersistence.State): Boolean =
        state.contextIdentities.none { it != null } &&
            state.entries.none { it.canonicalIdentity != null }
}
