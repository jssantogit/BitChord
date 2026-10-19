package com.music.bitchord.diagnostics

import com.music.bitchord.observability.RuntimeBaselineDistribution
import java.util.Locale
import kotlin.math.ceil

/**
 * O2 measurement-only model. OFF means capture disabled, not global tracing disabled:
 * the production diagnostics ring and debug logcat policy remain separate.
 */
internal enum class DiagnosticsCaptureMode(val wireName: String) {
    OFF("capture_off"),
    STANDARD("capture_standard"),
    VERBOSE("capture_verbose"),
}

/** Timed actual O2 fixture boundaries; no free-form stage/identity labels are accepted. */
internal enum class DiagnosticsOverheadPhase(val wireName: String) {
    STRUCTURED_MAP("structured_map"),
    BUFFER_APPEND("buffer_append"),
    EVENT_FORMAT("event_format"),
    WRITER_OFFER("writer_offer"),
    WRITER_DRAIN("writer_drain"),
    EXPORT_CREATE("export_create"),
}

internal data class DiagnosticsOverheadConfig(
    val repetitions: Int,
    val warmups: Int,
    val concurrentProducers: Int,
    val captureBytesLimit: Int,
    val writerQueueCapacity: Int,
) {
    init {
        require(repetitions > 0)
        require(warmups >= 0)
        require(concurrentProducers in 1..32)
        require(captureBytesLimit > 0)
        require(writerQueueCapacity > 0)
    }
}

internal data class DiagnosticsOverheadObservation(
    val mode: DiagnosticsCaptureMode,
    val phase: DiagnosticsOverheadPhase,
    val durationNanos: Long,
)

internal data class DiagnosticsModeCounters(
    val mode: DiagnosticsCaptureMode,
    val accepted: Int,
    val dropped: Long,
    val persistedBytes: Long,
) {
    init {
        require(accepted >= 0 && dropped >= 0L && persistedBytes >= 0L)
    }
}

internal data class DiagnosticsOverheadPhaseReport(
    val mode: DiagnosticsCaptureMode,
    val phase: DiagnosticsOverheadPhase,
    val distribution: RuntimeBaselineDistribution,
)

internal data class DiagnosticsOverheadReport(
    val config: DiagnosticsOverheadConfig,
    val phases: List<DiagnosticsOverheadPhaseReport>,
    val counters: List<DiagnosticsModeCounters>,
) {
    /**
     * Whitelisted-only export: no traces, UUIDs, serialized events, paths,
     * URLs, metadata keys/values, exception messages or session identifiers.
     */
    fun toSafeText(): String = buildString {
        appendLine("o2_diagnostics_overhead_version=0")
        appendLine("environment=jvm_fixture_not_android_device")
        appendLine("repetitions=" + config.repetitions)
        appendLine("warmups_excluded=" + config.warmups)
        appendLine("concurrent_producers=" + config.concurrentProducers)
        appendLine("capture_bytes_limit=" + config.captureBytesLimit)
        appendLine("writer_queue_capacity=" + config.writerQueueCapacity)
        counters.forEach { counter ->
            appendLine("mode=" + counter.mode.wireName +
                ";writer_accepted=" + counter.accepted +
                ";writer_dropped=" + counter.dropped +
                ";persisted_bytes=" + counter.persistedBytes)
        }
        phases.forEach { entry ->
            val sample = entry.distribution
            appendLine("mode=" + entry.mode.wireName + ";phase=" + entry.phase.wireName +
                ";n=" + sample.count + ";min_ns=" + sample.minNanos +
                ";max_ns=" + sample.maxNanos + ";spread_ns=" + sample.spreadNanos +
                ";p50_ns=" + (sample.p50Nanos?.toString() ?: "insufficient_samples") +
                ";p95_ns=" + (sample.p95Nanos?.toString() ?: "insufficient_samples"))
        }
    }
}

/**
 * Pure aggregation of already-measured timings from real components in tests.
 * Comparable fixture configuration is required, but distribution measurements
 * alone do not prove a speedup or justify a production behavior change.
 */
internal object DiagnosticsOverheadBaseline {
    fun summarize(
        config: DiagnosticsOverheadConfig,
        observations: List<DiagnosticsOverheadObservation>,
        counters: List<DiagnosticsModeCounters>,
    ): DiagnosticsOverheadReport {
        val distributions = observations
            .filter { it.durationNanos >= 0L }
            .groupBy { it.mode to it.phase }
            .entries
            .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second.ordinal }))
            .map { (key, measurements) ->
                DiagnosticsOverheadPhaseReport(
                    mode = key.first,
                    phase = key.second,
                    distribution = distribution(measurements.map { it.durationNanos }),
                )
            }
        return DiagnosticsOverheadReport(
            config = config,
            phases = distributions,
            counters = counters.sortedBy { it.mode.ordinal },
        )
    }

    private fun distribution(values: List<Long>): RuntimeBaselineDistribution {
        val sorted = values.sorted()
        require(sorted.isNotEmpty())
        fun percentile(p: Double): Long =
            sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return RuntimeBaselineDistribution(
            count = sorted.size,
            minNanos = sorted.first(),
            maxNanos = sorted.last(),
            p50Nanos = if (sorted.size >= 2) percentile(0.50) else null,
            p95Nanos = if (sorted.size >= 20) percentile(0.95) else null,
        )
    }
}
