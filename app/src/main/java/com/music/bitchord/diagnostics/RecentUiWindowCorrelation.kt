package com.music.bitchord.diagnostics

import kotlin.math.roundToInt

/**
 * D14: record only completed 10s frame aggregates, never per-frame detail.
 * A recent frame aggregate supplies *context*, not the cause of a recovered
 * main-thread stall. Its origin can precede the stall by several seconds.
 */
internal class RecentUiWindowCorrelation(
    private val maxAgeNanos: Long = 15_000_000_000L,
) {
    init { require(maxAgeNanos > 0L) }

    private data class Window(
        val context: String,
        val refreshRateHz: Int,
        val frameBudgetNanos: Long,
        val frames: Long,
        val overBudget: Long,
        val atNanos: Long,
    )
    private var latest: Window? = null

    @Synchronized
    fun record(
        context: String,
        refreshRateHz: Float,
        frameBudgetNanos: Long,
        frames: Long,
        overBudget: Long,
        atNanos: Long,
    ) {
        if (frames <= 0 || frameBudgetNanos <= 0 || atNanos < 0) return
        val safeContext = context.takeIf { it in CONTEXTS } ?: "other"
        val safeHz = refreshRateHz.takeIf { it.isFinite() && it in 1f..240f }
            ?.roundToInt() ?: 60
        latest = Window(
            safeContext, safeHz, frameBudgetNanos,
            frames.coerceAtMost(1_000_000L),
            overBudget.coerceIn(0L, frames.coerceAtMost(1_000_000L)),
            atNanos,
        )
    }

    @Synchronized
    fun at(nowNanos: Long): Map<String, String> {
        val window = latest ?: return mapOf("frameEvidence" to "missing", "causeStrength" to "unknown")
        val age = nowNanos - window.atNanos
        if (age < 0L || nowNanos < 0L) {
            return mapOf("frameEvidence" to "clock_unknown", "causeStrength" to "unknown")
        }
        if (age > maxAgeNanos) {
            return mapOf("frameEvidence" to "stale", "causeStrength" to "unknown")
        }
        return mapOf(
            "frameEvidence" to "prior_window",
            "causeStrength" to "unknown",
            "frameContext" to window.context,
            "frameRefreshHz" to window.refreshRateHz.toString(),
            "frameBudgetNs" to window.frameBudgetNanos.toString(),
            "frameFrames" to window.frames.toString(),
            "frameOverBudget" to window.overBudget.toString(),
            "frameAgeMs" to (age / 1_000_000L).toString(),
        )
    }

    private companion object {
        val CONTEXTS = setOf(
            "activity_start", "now_playing", "local_music", "home", "home_scroll",
            "other", "search", "library",
        )
    }
}

internal object RuntimeUiStallCorrelation {
    private val recent = RecentUiWindowCorrelation()

    fun record(
        context: String, refreshRateHz: Float, frameBudgetNanos: Long,
        frames: Long, overBudget: Long, atNanos: Long,
    ) = recent.record(context, refreshRateHz, frameBudgetNanos, frames, overBudget, atNanos)

    fun metadata(nowNanos: Long): Map<String, String> = recent.at(nowNanos)
}
