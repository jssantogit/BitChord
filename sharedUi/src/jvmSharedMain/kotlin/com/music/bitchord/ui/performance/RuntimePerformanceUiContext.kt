package com.music.bitchord.ui.performance

/**
 * Small platform seam for runtime-performance surface ownership and bounded UI
 * activity signals.
 *
 * Shared Compose UI stays unaware of Android diagnostics. Android debug builds
 * install a sink from the app module; desktop and non-debug processes keep the
 * default no-op behavior. High-frequency activity calls only update in-memory
 * fixed-cardinality counters; they never persist one event per frame,
 * recomposition or playback-position tick.
 */
object RuntimePerformanceUiContext {
    interface Sink {
        fun enter(owner: Any, context: String)
        fun update(owner: Any, context: String)
        fun leave(owner: Any)
        fun record(signal: RuntimePerformanceUiSignal) = Unit
        fun setState(key: RuntimePerformanceUiStateKey, value: RuntimePerformanceUiStateValue) = Unit
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

    fun record(signal: RuntimePerformanceUiSignal) {
        sink?.record(signal)
    }

    fun setState(key: RuntimePerformanceUiStateKey, value: RuntimePerformanceUiStateValue) {
        sink?.setState(key, value)
    }
}
