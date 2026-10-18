package com.music.bitchord.playback

import com.music.bitchord.diagnostics.BoundedPerformanceProbe
import com.music.bitchord.diagnostics.DiagnosticCategory

/**
 * Stable, low-cardinality operation names for the bounded playback probes.
 *
 * These timings intentionally cover only synchronous dispatch/handler work.
 * End-to-end latency such as seek-to-ready or command-to-audible-result belongs
 * to the later stall/latency diagnostics slice.
 */
internal enum class PlaybackPerformanceOperation(val eventName: String) {
    SEEK("runtime_playback_seek"),
    QUEUE_MUTATION("runtime_playback_queue_mutation"),
    QUEUE_REBUILD("runtime_playback_queue_rebuild"),
    TRACK_TRANSITION("runtime_playback_track_transition"),
}

internal class PlaybackPerformanceProbe(
    private val bounded: BoundedPerformanceProbe = BoundedPerformanceProbe(),
) {
    fun <T> measure(
        operation: PlaybackPerformanceOperation,
        block: () -> T,
    ): T = bounded.measure(
        category = DiagnosticCategory.PLAYER,
        name = operation.eventName,
        block = block,
    )
}

/** Process-local production probe shared by the playback service seams. */
internal object PlaybackPerformance {
    private val probe = PlaybackPerformanceProbe()

    fun <T> measure(
        operation: PlaybackPerformanceOperation,
        block: () -> T,
    ): T = probe.measure(operation, block)
}
