package com.music.bitchord.diagnostics

/**
 * One bounded operation timing sample.
 *
 * Unlike frame collection, this is intended for explicit operation boundaries
 * such as queue rebuild or stream resolution. One invocation emits exactly one
 * sample and never creates position/frame-tick telemetry.
 */
data class BoundedPerformanceSample(
    val category: DiagnosticCategory,
    val name: String,
    val durationNanos: Long,
    val metadata: Map<String, String> = emptyMap(),
)

class BoundedPerformanceProbe(
    private val nowNanos: () -> Long = System::nanoTime,
    private val sink: (BoundedPerformanceSample) -> Unit = { sample ->
        Diagnostics.event(
            category = sample.category,
            name = sample.name,
            durationNanos = sample.durationNanos,
            metadata = sample.metadata,
        )
    },
) {
    inline fun <T> measure(
        category: DiagnosticCategory,
        name: String,
        metadata: Map<String, String> = emptyMap(),
        block: () -> T,
    ): T {
        val started = nowNanos()
        try {
            return block()
        } finally {
            sink(
                BoundedPerformanceSample(
                    category = category,
                    name = name,
                    durationNanos = nowNanos() - started,
                    metadata = metadata,
                ),
            )
        }
    }

    suspend inline fun <T> measureSuspend(
        category: DiagnosticCategory,
        name: String,
        metadata: Map<String, String> = emptyMap(),
        crossinline block: suspend () -> T,
    ): T {
        val started = nowNanos()
        try {
            return block()
        } finally {
            sink(
                BoundedPerformanceSample(
                    category = category,
                    name = name,
                    durationNanos = nowNanos() - started,
                    metadata = metadata,
                ),
            )
        }
    }
}
