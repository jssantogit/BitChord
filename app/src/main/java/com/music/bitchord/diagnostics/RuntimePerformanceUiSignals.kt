package com.music.bitchord.diagnostics

/**
 * Bounded UI state attached to runtime frame windows.
 *
 * Feature ownership is represented by small in-memory owner sets so overlapping
 * Compose surfaces do not flap the aggregate state on/off. Churn is a scalar
 * counter reset whenever the surrounding frame window is emitted; no
 * recomposition or frame produces its own diagnostics event.
 */
internal class RuntimePerformanceUiSignals {
    private val dynamicBlurOwners = mutableSetOf<Any>()
    private val canvasArtworkOwners = mutableSetOf<Any>()
    private var nowPlayingRecompositions = 0L

    @Synchronized
    fun dynamicBlurStateWouldChange(owner: Any, active: Boolean): Boolean =
        aggregateStateWouldChange(dynamicBlurOwners, owner, active)

    @Synchronized
    fun canvasArtworkStateWouldChange(owner: Any, active: Boolean): Boolean =
        aggregateStateWouldChange(canvasArtworkOwners, owner, active)

    @Synchronized
    fun setDynamicBlur(owner: Any, active: Boolean) {
        updateOwners(dynamicBlurOwners, owner, active)
    }

    @Synchronized
    fun setCanvasArtwork(owner: Any, active: Boolean) {
        updateOwners(canvasArtworkOwners, owner, active)
    }

    @Synchronized
    fun recordNowPlayingRecomposition() {
        nowPlayingRecompositions += 1L
    }

    @Synchronized
    fun resetCounters() {
        nowPlayingRecompositions = 0L
    }

    @Synchronized
    fun snapshotAndResetCounters(): RuntimePerformanceUiSignalsSnapshot {
        val snapshot = RuntimePerformanceUiSignalsSnapshot(
            dynamicBlurActive = dynamicBlurOwners.isNotEmpty(),
            canvasArtworkActive = canvasArtworkOwners.isNotEmpty(),
            nowPlayingRecompositions = nowPlayingRecompositions,
        )
        nowPlayingRecompositions = 0L
        return snapshot
    }

    private fun aggregateStateWouldChange(
        owners: Set<Any>,
        owner: Any,
        active: Boolean,
    ): Boolean {
        val before = owners.isNotEmpty()
        val after = when {
            active -> true
            owner !in owners -> before
            owners.size > 1 -> true
            else -> false
        }
        return before != after
    }

    private fun updateOwners(owners: MutableSet<Any>, owner: Any, active: Boolean) {
        if (active) {
            owners += owner
        } else {
            owners -= owner
        }
    }
}

internal data class RuntimePerformanceUiSignalsSnapshot(
    val dynamicBlurActive: Boolean,
    val canvasArtworkActive: Boolean,
    val nowPlayingRecompositions: Long,
)

internal fun RuntimePerformanceUiSignalsSnapshot.toDiagnosticMetadata(): Map<String, String> = linkedMapOf(
    "dynamicBlurActive" to dynamicBlurActive.toString(),
    "canvasArtworkActive" to canvasArtworkActive.toString(),
    "nowPlayingRecompositions" to nowPlayingRecompositions.toString(),
)
