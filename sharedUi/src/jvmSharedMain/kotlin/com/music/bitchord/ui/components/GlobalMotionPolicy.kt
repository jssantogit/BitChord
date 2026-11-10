package com.music.bitchord.ui.components

/**
 * Shared Android/Compose motion policy. A route or component must opt into a
 * particular role: these values are NOT universal Apple Music timings.
 *
 * The only Apple-backed duration here is the conditional Loader fade-out:
 * am-descomp/docs/motion/home-and-content.md traces alpha -> 0 in 400 ms with
 * DecelerateInterpolator. This does not establish a 400ms content/route fade.
 *
 * The header and shimmer values below are pre-existing BitChord timings,
 * centralized to ensure that our Reduce animations setting is obeyed.
 */
object GlobalMotionPolicy {
    const val loaderExitMillis = 400
    const val headerChromeMillis = 220
    // Existing BitChord bottom-bar tint duration, reused by search selectors.
    const val selectionTintMillis = 180
    const val skeletonSweepMillis = 1400

    fun duration(baseMillis: Int, reduced: Boolean): Int {
        require(baseMillis >= 0) { "Motion duration must be nonnegative" }
        return if (reduced) 0 else baseMillis
    }

    fun shimmerEnabled(reduced: Boolean): Boolean = !reduced

    fun indeterminateRefreshEnabled(refreshing: Boolean, reduced: Boolean): Boolean =
        refreshing && !reduced

    /** No pressed scale is displayed when Reduce animations is enabled. */
    fun pressedScale(pressed: Boolean, reduced: Boolean, activeScale: Float = 0.98f): Float =
        if (reduced || !pressed) 1f else activeScale
}
