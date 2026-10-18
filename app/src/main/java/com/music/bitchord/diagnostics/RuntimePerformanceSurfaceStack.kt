package com.music.bitchord.diagnostics

/**
 * Keeps the visible runtime-performance context deterministic when a modal
 * surface (notably Now Playing) is drawn in a separate Android Window.
 *
 * Base navigation may keep changing behind an overlay, but the overlay owns the
 * measurements until it disappears. The latest base context is then restored.
 */
internal class RuntimePerformanceSurfaceStack(initialBaseContext: String) {
    private var baseContext = initialBaseContext
    private var overlayContext: String? = null

    val currentContext: String
        get() = overlayContext ?: baseContext

    fun setBaseContext(context: String) {
        baseContext = context
    }

    fun showOverlay(context: String) {
        overlayContext = context
    }

    fun hideOverlay() {
        overlayContext = null
    }
}
