package com.music.bitchord.diagnostics

import com.music.bitchord.ui.performance.RuntimePerformanceUiSignal
import com.music.bitchord.ui.performance.RuntimePerformanceUiStateKey
import com.music.bitchord.ui.performance.RuntimePerformanceUiStateValue

/**
 * Capture-aware bounded aggregation for UI churn and feature state.
 *
 * High-frequency UI callbacks only increment in-memory fixed-cardinality counters.
 * Diagnostics persistence happens when the surrounding monitor flushes this window.
 */
internal class RuntimePerformanceUiActivityWindow(
    initialContext: String,
    private val onSnapshot: (RuntimePerformanceUiActivitySnapshot) -> Unit,
) {
    private var context = initialContext
    private var captureActive = false
    private val signalCounts = mutableMapOf<RuntimePerformanceUiSignal, Long>()
    private val states = mutableMapOf<RuntimePerformanceUiStateKey, RuntimePerformanceUiStateValue>()
    private val stateChangeCounts = mutableMapOf<RuntimePerformanceUiStateKey, Long>()

    @Synchronized
    fun setCaptureActive(active: Boolean) {
        if (active == captureActive) return
        captureActive = active
        clearPartialWindow()
    }

    @Synchronized
    fun setContext(context: String) {
        if (context == this.context) return
        flushLocked()
        this.context = context
    }

    @Synchronized
    fun record(signal: RuntimePerformanceUiSignal) {
        if (!captureActive) return
        signalCounts[signal] = (signalCounts[signal] ?: 0L) + 1L
    }

    @Synchronized
    fun setState(
        key: RuntimePerformanceUiStateKey,
        value: RuntimePerformanceUiStateValue,
    ) {
        val previous = states.put(key, value)
        if (!captureActive || previous == value) return
        stateChangeCounts[key] = (stateChangeCounts[key] ?: 0L) + 1L
    }

    @Synchronized
    fun flush() {
        flushLocked()
    }

    private fun flushLocked() {
        if (!captureActive || (signalCounts.isEmpty() && stateChangeCounts.isEmpty())) return
        onSnapshot(
            RuntimePerformanceUiActivitySnapshot(
                context = context,
                signalCounts = signalCounts.toMap(),
                states = states.toMap(),
                stateChangeCounts = stateChangeCounts.toMap(),
            ),
        )
        clearPartialWindow()
    }

    private fun clearPartialWindow() {
        signalCounts.clear()
        stateChangeCounts.clear()
    }
}

internal data class RuntimePerformanceUiActivitySnapshot(
    val context: String,
    val signalCounts: Map<RuntimePerformanceUiSignal, Long>,
    val states: Map<RuntimePerformanceUiStateKey, RuntimePerformanceUiStateValue>,
    val stateChangeCounts: Map<RuntimePerformanceUiStateKey, Long>,
)

internal fun RuntimePerformanceUiActivitySnapshot.toDiagnosticMetadata(): Map<String, String> = buildMap {
    put("context", context)
    RuntimePerformanceUiSignal.entries.forEach { signal ->
        signalCounts[signal]?.takeIf { it > 0L }?.let { put(signal.metadataKey, it.toString()) }
    }
    RuntimePerformanceUiStateKey.entries.forEach { key ->
        states[key]?.let { put(key.metadataKey, it.metadataValue) }
        stateChangeCounts[key]?.takeIf { it > 0L }?.let { put(key.changesMetadataKey, it.toString()) }
    }
}
