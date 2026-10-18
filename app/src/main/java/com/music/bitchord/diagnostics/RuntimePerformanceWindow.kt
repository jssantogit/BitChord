package com.music.bitchord.diagnostics

import java.util.Locale

/**
 * Groups frame timings into bounded context windows before emitting diagnostics.
 *
 * A context switch flushes the previous non-empty window so long captures do
 * not retain individual frames and the report can still distinguish screens
 * and interaction states such as idle vs scrolling.
 */
internal class RuntimePerformanceWindow(
    initialContext: String,
    private val onSnapshot: (RuntimePerformanceSnapshot) -> Unit,
) {
    private var context = initialContext
    private val aggregator = RuntimePerformanceAggregator()

    @Synchronized
    fun recordFrame(durationNanos: Long, frameBudgetNanos: Long) {
        aggregator.recordFrame(durationNanos, frameBudgetNanos)
    }

    @Synchronized
    fun setContext(newContext: String) {
        if (newContext == context) return
        emitCurrentIfNotEmpty()
        context = newContext
    }

    @Synchronized
    fun flush() {
        emitCurrentIfNotEmpty()
    }

    private fun emitCurrentIfNotEmpty() {
        val snapshot = aggregator.snapshot(context)
        if (snapshot.totalFrames == 0L) return
        aggregator.reset()
        onSnapshot(snapshot)
    }
}

/**
 * Stable, bounded metadata written into a single diagnostics event per window.
 */
internal fun RuntimePerformanceSnapshot.toDiagnosticMetadata(): Map<String, String> {
    val overBudgetPercent = if (totalFrames == 0L) {
        0.0
    } else {
        overBudgetFrames.toDouble() * 100.0 / totalFrames.toDouble()
    }

    return linkedMapOf(
        "context" to context,
        "frames" to totalFrames.toString(),
        "overBudget" to overBudgetFrames.toString(),
        "overBudgetPercent" to String.format(Locale.US, "%.2f", overBudgetPercent),
        "p50Ms" to p50Ms.toString(),
        "p90Ms" to p90Ms.toString(),
        "p95Ms" to p95Ms.toString(),
        "p99Ms" to p99Ms.toString(),
        "maxMs" to maxMs.toString(),
        "over33Ms" to over33Ms.toString(),
        "over50Ms" to over50Ms.toString(),
        "over100Ms" to over100Ms.toString(),
        "over250Ms" to over250Ms.toString(),
        "over500Ms" to over500Ms.toString(),
    )
}
