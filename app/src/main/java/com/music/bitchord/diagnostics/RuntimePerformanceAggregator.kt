package com.music.bitchord.diagnostics

import kotlin.math.ceil

/**
 * Bounded frame-timing aggregation for runtime diagnostics.
 *
 * Frame callbacks can run for hours while a diagnostic capture is active, so
 * this deliberately stores a fixed histogram rather than individual samples.
 * The final bucket is overflow: durations above [maxTrackedDurationMs] still
 * contribute to counts and the exact maximum without growing memory usage.
 */
internal class RuntimePerformanceAggregator(
    private val maxTrackedDurationMs: Int = DEFAULT_MAX_TRACKED_DURATION_MS,
) {
    init {
        require(maxTrackedDurationMs >= 1) { "maxTrackedDurationMs must be positive" }
    }

    private val histogram = LongArray(maxTrackedDurationMs + 2)
    private var totalFrames = 0L
    private var overBudgetFrames = 0L
    private var over33Ms = 0L
    private var over50Ms = 0L
    private var over100Ms = 0L
    private var over250Ms = 0L
    private var over500Ms = 0L
    private var maxMs = 0L

    internal val storageBucketCount: Int
        get() = histogram.size

    @Synchronized
    fun recordFrame(durationNanos: Long, frameBudgetNanos: Long) {
        val safeDurationNanos = durationNanos.coerceAtLeast(0L)
        val durationMs = nanosToCeilingMs(safeDurationNanos)

        totalFrames += 1
        if (frameBudgetNanos > 0L && safeDurationNanos > frameBudgetNanos) {
            overBudgetFrames += 1
        }
        if (durationMs > 33L) over33Ms += 1
        if (durationMs > 50L) over50Ms += 1
        if (durationMs > 100L) over100Ms += 1
        if (durationMs > 250L) over250Ms += 1
        if (durationMs > 500L) over500Ms += 1
        if (durationMs > maxMs) maxMs = durationMs

        val bucket = if (durationMs > maxTrackedDurationMs.toLong()) {
            maxTrackedDurationMs + 1
        } else {
            durationMs.toInt()
        }
        histogram[bucket] += 1
    }

    @Synchronized
    fun snapshot(context: String): RuntimePerformanceSnapshot = RuntimePerformanceSnapshot(
        context = context,
        totalFrames = totalFrames,
        overBudgetFrames = overBudgetFrames,
        p50Ms = percentile(0.50),
        p90Ms = percentile(0.90),
        p95Ms = percentile(0.95),
        p99Ms = percentile(0.99),
        maxMs = maxMs,
        over33Ms = over33Ms,
        over50Ms = over50Ms,
        over100Ms = over100Ms,
        over250Ms = over250Ms,
        over500Ms = over500Ms,
    )

    @Synchronized
    fun reset() {
        histogram.fill(0L)
        totalFrames = 0L
        overBudgetFrames = 0L
        over33Ms = 0L
        over50Ms = 0L
        over100Ms = 0L
        over250Ms = 0L
        over500Ms = 0L
        maxMs = 0L
    }

    private fun percentile(fraction: Double): Long {
        if (totalFrames == 0L) return 0L
        val rank = ceil(totalFrames * fraction).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        histogram.forEachIndexed { index, count ->
            cumulative += count
            if (cumulative >= rank) {
                return if (index <= maxTrackedDurationMs) {
                    index.toLong()
                } else {
                    // The exact distribution above the tracked range is intentionally
                    // not retained. Returning the tracked ceiling keeps the percentile
                    // bounded while [maxMs] preserves the true worst frame.
                    maxTrackedDurationMs.toLong() + 1L
                }
            }
        }
        return maxMs
    }

    private fun nanosToCeilingMs(nanos: Long): Long {
        val whole = nanos / NANOS_PER_MS
        return if (nanos % NANOS_PER_MS == 0L) whole else whole + 1L
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val DEFAULT_MAX_TRACKED_DURATION_MS = 2_000
    }
}

internal data class RuntimePerformanceSnapshot(
    val context: String,
    val totalFrames: Long,
    val overBudgetFrames: Long,
    val p50Ms: Long,
    val p90Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maxMs: Long,
    val over33Ms: Long,
    val over50Ms: Long,
    val over100Ms: Long,
    val over250Ms: Long,
    val over500Ms: Long,
)
