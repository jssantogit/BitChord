package com.music.bitchord.diagnostics

/**
 * Keeps the visible runtime-performance context deterministic when navigation
 * surfaces overlap during Compose transitions and when a modal surface (notably
 * Now Playing) is drawn in a separate Android Window.
 *
 * Scoped base owners are ordered by visibility: the newest owner wins, while a
 * covered owner may keep updating its interaction state without stealing the
 * visible context. Removing the top owner restores the latest state of the
 * surface underneath. A modal overlay still wins over every base surface.
 */
internal class RuntimePerformanceSurfaceStack(initialBaseContext: String) {
    private data class BaseSurface(val owner: Any, var context: String)

    private var fallbackBaseContext = initialBaseContext
    private val baseSurfaces = mutableListOf<BaseSurface>()
    private var overlayContext: String? = null

    private val currentBaseContext: String
        get() = baseSurfaces.lastOrNull()?.context ?: fallbackBaseContext

    val currentContext: String
        get() = overlayContext ?: currentBaseContext

    /** Legacy/fallback base context for callers that do not own a scoped surface. */
    fun setBaseContext(context: String) {
        fallbackBaseContext = context
    }

    /** Makes [owner] the newest visible base surface. Re-entering moves it to the top. */
    fun enterBaseSurface(owner: Any, context: String) {
        baseSurfaces.removeAll { it.owner === owner }
        baseSurfaces += BaseSurface(owner, context)
    }

    /** Updates a surface in place so a covered owner cannot steal visibility. */
    fun updateBaseSurface(owner: Any, context: String) {
        baseSurfaces.lastOrNull { it.owner === owner }?.context = context
    }

    /** Removes exactly this owner; if it was visible the surface underneath is restored. */
    fun leaveBaseSurface(owner: Any) {
        baseSurfaces.removeAll { it.owner === owner }
    }

    fun showOverlay(context: String) {
        overlayContext = context
    }

    fun hideOverlay() {
        overlayContext = null
    }
}
