package com.music.bitchord.diagnostics

/**
 * Stable base-surface labels for bounded runtime frame windows.
 *
 * Keep the classification independent of Compose/navigation types so the
 * contract stays unit-testable. The Android UI owns translating its current
 * navigation state into these booleans.
 */
internal fun runtimePerformanceContext(
    isHome: Boolean,
    homeScrollInProgress: Boolean,
    isLocalMusicRoot: Boolean,
): String = when {
    isLocalMusicRoot -> "local_music"
    isHome && homeScrollInProgress -> "home_scroll"
    isHome -> "home"
    else -> "other"
}
