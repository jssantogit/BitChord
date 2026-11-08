package com.music.bitchord.ui.components

/**
 * The experiment adds a *visual* Radio slot without changing the four routes
 * already persisted by MainActivity: Home=0, Explore=1, Library=2, Search=3.
 *
 * Index 2 is deliberately not navigable. Do not save visual indices in place
 * of the existing route indices; old restored state must keep its meaning.
 */
internal const val APPLE_MUSIC_RADIO_VISUAL_INDEX = 2
internal const val APPLE_MUSIC_VISIBLE_TAB_COUNT = 5

internal fun appleMusicVisualIndexForRoute(routeIndex: Int): Int {
    require(routeIndex in 0..3) { "Unknown route tab: $routeIndex" }
    return if (routeIndex >= APPLE_MUSIC_RADIO_VISUAL_INDEX) routeIndex + 1 else routeIndex
}

internal fun appleMusicRouteForVisualIndex(visualIndex: Int): Int? {
    require(visualIndex in 0 until APPLE_MUSIC_VISIBLE_TAB_COUNT) {
        "Unknown Apple Music visual tab: $visualIndex"
    }
    return when {
        visualIndex == APPLE_MUSIC_RADIO_VISUAL_INDEX -> null
        visualIndex > APPLE_MUSIC_RADIO_VISUAL_INDEX -> visualIndex - 1
        else -> visualIndex
    }
}
