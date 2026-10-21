package com.music.bitchord.diagnostics

import kotlin.math.ceil

enum class DiagnosticPercentileEvidence { MEASURED, INSUFFICIENT }

data class DiagnosticBottleneckSummary(
    val feature: String,
    val operation: String,
    val phase: String,
    val sampleCount: Int,
    val maxObservedNanos: Long,
    val p50Nanos: Long?,
    val p95Nanos: Long?,
    val percentileEvidence: DiagnosticPercentileEvidence,
    val samplesDropped: Int,
) {
    /** Parallel and nested spans cannot prove a wall-clock critical path. */
    val provesCriticalPath: Boolean get() = false
}

/**
 * D8: read-only post-capture projection over existing span timers. No hot-path
 * probes, frame logs, sorting on player/UI threads or extra persistence.
 */
object DiagnosticBottleneckAnalysis {
    private const val MIN_PERCENTILE_SAMPLES = 20
    private const val MAX_EVENTS = 1_024
    private const val MAX_GROUPS = 32
    private const val MAX_SAMPLES_PER_GROUP = 128
    private val safeToken = Regex("^[a-zA-Z0-9_-]{1,96}$")
    private val phases = setOf("queue", "execution", "external")

    fun summarize(
        events: List<DiagnosticEvent>,
        captureEnabled: Boolean,
    ): List<DiagnosticBottleneckSummary> {
        if (!captureEnabled) return emptyList()
        data class Group(val samples: MutableList<Long> = ArrayList(), var max: Long = 0L, var dropped: Int = 0)
        val groups = linkedMapOf<Triple<String, String, String>, Group>()
        events.takeLast(MAX_EVENTS).forEach { event ->
            val elapsed = event.durationNanos ?: return@forEach
            if (elapsed < 0L || event.name != "trace_span") return@forEach
            val feature = event.metadata["feature"] ?: return@forEach
            val operation = event.metadata["operation"] ?: return@forEach
            if (!safeToken.matches(feature) || !safeToken.matches(operation)) return@forEach
            val phase = event.metadata["phase"]?.takeIf { it in phases } ?: "unclassified"
            val key = Triple(feature, operation, phase)
            val group = groups[key] ?: if (groups.size < MAX_GROUPS) {
                Group().also { groups[key] = it }
            } else return@forEach
            group.max = maxOf(group.max, elapsed)
            if (group.samples.size < MAX_SAMPLES_PER_GROUP) {
                group.samples.add(elapsed)
            } else {
                group.dropped++
            }
        }
        return groups.map { (key, group) ->
            val valid = group.samples.size >= MIN_PERCENTILE_SAMPLES && group.dropped == 0
            val sorted = if (valid) group.samples.sorted() else emptyList()
            fun percentile(p: Double): Long? = if (!valid) null else {
                sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
            }
            DiagnosticBottleneckSummary(
                feature = key.first, operation = key.second, phase = key.third,
                sampleCount = group.samples.size + group.dropped,
                maxObservedNanos = group.max,
                p50Nanos = percentile(0.50),
                p95Nanos = percentile(0.95),
                percentileEvidence = if (valid) DiagnosticPercentileEvidence.MEASURED
                    else DiagnosticPercentileEvidence.INSUFFICIENT,
                samplesDropped = group.dropped,
            )
        }.sortedWith(compareByDescending<DiagnosticBottleneckSummary> { it.maxObservedNanos }
            .thenBy { it.feature }.thenBy { it.operation }).take(MAX_GROUPS)
    }
}
