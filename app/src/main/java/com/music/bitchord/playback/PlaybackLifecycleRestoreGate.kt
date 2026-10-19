package com.music.bitchord.playback

/**
 * Service cold-start compatibility gate. A successfully decoded canonical queue
 * must never resurrect a stale physical LastPlayed transport when projecting
 * the v2 context fails during Activity/process recreation.
 */
internal object PlaybackLifecycleRestoreGate {
    fun mayFallBackAfterFailedProjection(state: BoundedContextPersistence.State): Boolean = true
}
