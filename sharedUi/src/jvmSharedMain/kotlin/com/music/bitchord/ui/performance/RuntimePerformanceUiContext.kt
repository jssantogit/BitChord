package com.music.bitchord.ui.performance

/**
 * Small platform seam for runtime-performance surface ownership.
 *
 * Shared Compose UI stays unaware of Android diagnostics. Android debug builds
 * install a sink from the app module; desktop and non-debug processes keep the
 * default no-op behavior. Calls are surface-boundary updates only — never one
 * event per frame or playback-position tick.
 */
object RuntimePerformanceUiContext {
    interface Sink {
        fun enter(owner: Any, context: String)
        fun update(owner: Any, context: String)
        fun leave(owner: Any)
    }

    @Volatile
    private var sink: Sink? = null

    val enabled: Boolean
        get() = sink != null

    fun install(sink: Sink?) {
        this.sink = sink
    }

    fun enter(owner: Any, context: String) {
        sink?.enter(owner, context)
    }

    fun update(owner: Any, context: String) {
        sink?.update(owner, context)
    }

    fun leave(owner: Any) {
        sink?.leave(owner)
    }
}
