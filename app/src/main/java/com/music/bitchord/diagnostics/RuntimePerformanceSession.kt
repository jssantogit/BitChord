package com.music.bitchord.diagnostics

/**
 * Capture-aware owner for runtime frame windows.
 *
 * Context may keep changing while capture is disabled, but no frame sampled
 * before the explicit capture boundary is retained. Stopping a capture flushes
 * the last non-empty window once, then later frames are ignored until capture
 * is enabled again.
 */
internal class RuntimePerformanceSession(
    initialContext: String,
    onSnapshot: (RuntimePerformanceSnapshot) -> Unit,
) {
    private val window = RuntimePerformanceWindow(initialContext, onSnapshot)
    private var captureActive = false

    @Synchronized
    fun setCaptureActive(active: Boolean) {
        if (active == captureActive) return
        if (captureActive) {
            window.flush()
        }
        captureActive = active
    }

    @Synchronized
    fun setContext(context: String) {
        window.setContext(context)
    }

    @Synchronized
    fun recordFrame(durationNanos: Long, frameBudgetNanos: Long) {
        if (!captureActive) return
        window.recordFrame(durationNanos, frameBudgetNanos)
    }

    @Synchronized
    fun flush() {
        if (captureActive) {
            window.flush()
        }
    }
}
