package com.music.bitchord.observability

import java.util.Locale
import kotlin.math.ceil

/**
 * O1 read-only analysis of existing typed trace points. This component never installs
 * an observer, changes playback/provider/ranking work, persists captures or exports
 * trace IDs/attributes. It is intended for explicitly collected, bounded test runs.
 */
internal enum class RuntimeBaselineScenario {
    PLAYBACK_COLD,
    PLAYBACK_WARM,
    PROVIDER_COLD,
    PROVIDER_WARM,
    PROVIDER_FAILURE_RECONNECT,
    RECOMMENDATION_LIBRARY,
    STALL_RECOVERY,
}

internal data class RuntimeBaselineConfig(
    val scenario: RuntimeBaselineScenario,
    val repetitions: Int,
    val warmupRuns: Int,
    val concurrency: Int,
    val traceMode: TraceMode,
) {
    init {
        require(repetitions > 0) { "repetitions must be positive" }
        require(warmupRuns >= 0) { "warmupRuns must not be negative" }
        require(concurrency in 1..32) { "concurrency out of bounds" }
    }
}

/** Quantiles use nearest rank. p95 is withheld below 20 observations. */
internal data class RuntimeBaselineDistribution(
    val count: Int,
    val minNanos: Long,
    val maxNanos: Long,
    val p50Nanos: Long?,
    val p95Nanos: Long?,
) {
    val spreadNanos: Long get() = maxNanos - minNanos
}

internal data class RuntimeBaselinePhase(
    val operation: String,
    val stage: String,
    val outcome: TraceOutcome,
    val distribution: RuntimeBaselineDistribution,
)

internal data class RuntimeBaselineReport(
    val config: RuntimeBaselineConfig,
    val correlatedTraces: Int,
    val incompleteTraces: Int,
    val phases: List<RuntimeBaselinePhase>,
) {
    /**
     * Stable, explicit allowlisted diagnostic fields ONLY. No trace IDs, original
     * attributes, endpoints, provider IDs, recordings or error messages.
     */
    fun toSafeText(): String = buildString {
        appendLine("baseline_version=o1")
        appendLine("environment=jvm_test_or_explicit_caller_context")
        appendLine("scenario=${config.scenario.name.lowercase(Locale.ROOT)}")
        appendLine("repetition_count=${config.repetitions}")
        appendLine("warmup_runs=${config.warmupRuns}")
        appendLine("concurrency=${config.concurrency}")
        appendLine("trace_mode=${config.traceMode.name.lowercase(Locale.ROOT)}")
        appendLine("correlated_traces=$correlatedTraces")
        appendLine("incomplete_traces=$incompleteTraces")
        phases.forEach { phase ->
            val stats = phase.distribution
            appendLine(
                "phase=${phase.operation}/${phase.stage};outcome=${phase.outcome.name.lowercase(Locale.ROOT)}" +
                    ";n=${stats.count};min_ns=${stats.minNanos};max_ns=${stats.maxNanos}" +
                    ";spread_ns=${stats.spreadNanos};p50_ns=${stats.p50Nanos ?: "insufficient_samples"}" +
                    ";p95_ns=${stats.p95Nanos ?: "insufficient_samples"}",
            )
        }
    }
}

internal object RuntimePerformanceBaseline {
    private const val STALL_THRESHOLD_NANOS = 250_000_000L

    /** Only PR #39's known operation/stage taxonomy is accepted into output. */
    private val operations: Map<String, Pair<TraceFeature, Set<String>>> = mapOf(
        "play_to_audible" to (TraceFeature.PLAYBACK to emptySet()),
        "opensubsonic_runtime_resolve" to (TraceFeature.PROVIDER to setOf("access_lookup")),
        "library_rank" to (TraceFeature.RECOMMENDATION to setOf(
            "candidate_generation",
            "personal_rank",
            "session_rerank",
            "artist_diversity",
            "controlled_exploration",
        )),
    )

    private data class TraceKey(val feature: TraceFeature, val operation: String, val traceId: String)
    private data class Sample(
        val operation: String,
        val stage: String,
        val outcome: TraceOutcome,
        val durationNanos: Long,
    )
    private data class PhaseKey(val operation: String, val stage: String, val outcome: TraceOutcome)

    /**
     * A TRACE_START/TRACE_END pair is matched in arrival order, even if the same
     * private ID is re-used in a separate sequential run. Orphans are counted but
     * never mixed into timed samples. Events have NO phase duration by contract;
     * only SPAN and TRACE_END carry eligible timing values.
     *
     * recoveredStallsNanos contains observations emitted AFTER main-thread recovery,
     * not heartbeat samples or a synthetic stall inferred from a missing event.
     */
    fun summarize(
        config: RuntimeBaselineConfig,
        points: List<StructuredTracePoint>,
        recoveredStallsNanos: List<Long> = emptyList(),
    ): RuntimeBaselineReport {
        val active = linkedMapOf<TraceKey, MutableList<Sample>>()
        val samples = mutableListOf<Sample>()
        var correlated = 0
        var incomplete = 0

        points.forEach { point ->
            val definition = operations[point.operation] ?: return@forEach
            if (point.feature != definition.first) return@forEach
            val key = TraceKey(point.feature, point.operation, point.traceId)
            when (point.kind) {
                TracePointKind.TRACE_START -> {
                    if (active.put(key, mutableListOf()) != null) incomplete += 1
                }
                TracePointKind.SPAN -> {
                    val stage = point.stage ?: return@forEach
                    val duration = point.durationNanos ?: return@forEach
                    if (stage in definition.second && duration >= 0L) {
                        active[key]?.add(Sample(point.operation, stage, point.outcome, duration))
                    }
                }
                TracePointKind.TRACE_END -> {
                    val spans = active.remove(key)
                    val duration = point.durationNanos
                    if (spans == null || duration == null || duration < 0L ||
                        point.outcome == TraceOutcome.STARTED
                    ) {
                        incomplete += 1
                    } else {
                        correlated += 1
                        samples += Sample(point.operation, "total", point.outcome, duration)
                        samples.addAll(spans)
                    }
                }
                TracePointKind.EVENT -> Unit
            }
        }
        incomplete += active.size

        recoveredStallsNanos.filter { it >= STALL_THRESHOLD_NANOS }.forEach { duration ->
            samples += Sample("main_thread_stall", "recovered", TraceOutcome.DEGRADED, duration)
        }

        val phases = samples.groupBy { PhaseKey(it.operation, it.stage, it.outcome) }
            .map { (key, values) ->
                RuntimeBaselinePhase(
                    operation = key.operation,
                    stage = key.stage,
                    outcome = key.outcome,
                    distribution = distribution(values.map(Sample::durationNanos)),
                )
            }
            .sortedWith(compareBy({ it.operation }, { it.stage }, { it.outcome.name }))
        return RuntimeBaselineReport(config, correlated, incomplete, phases)
    }

    private fun distribution(values: List<Long>): RuntimeBaselineDistribution {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        fun nearestRank(percentile: Double) =
            sorted[(ceil(sorted.size * percentile).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return RuntimeBaselineDistribution(
            count = sorted.size,
            minNanos = sorted.first(),
            maxNanos = sorted.last(),
            p50Nanos = if (sorted.size >= 2) nearestRank(0.50) else null,
            p95Nanos = if (sorted.size >= 20) nearestRank(0.95) else null,
        )
    }
}
