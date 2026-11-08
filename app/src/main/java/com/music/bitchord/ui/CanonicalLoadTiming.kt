package com.music.bitchord.ui

import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics

/** Low-cardinality, identifier-free timing buckets for real Android smoke.
 * Do not record query text, MBIDs, URLs, or device file paths. */
internal fun canonicalLoadLatencyBucket(ms: Long): String = when {
    ms < 250L -> "under_250ms"
    ms < 1_000L -> "250_999ms"
    ms < 3_000L -> "1_3s"
    ms < 8_000L -> "3_8s"
    else -> "over_8s"
}

internal fun canonicalLoadCountBucket(count: Int): String = when {
    count <= 0 -> "zero"
    count <= 5 -> "1_5"
    count <= 25 -> "6_25"
    else -> "over_25"
}

/** Best-effort instrumentation, not on the critical path of catalog rendering. */
internal fun recordCanonicalLoadTiming(stage: String, startedNanos: Long, count: Int) {
    val elapsedMs = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
    runCatching {
        Diagnostics.event(
            category = DiagnosticCategory.UI,
            name = "canonical_load_timing",
            metadata = mapOf(
                "stage" to stage,
                "latency" to canonicalLoadLatencyBucket(elapsedMs),
                "rows" to canonicalLoadCountBucket(count),
            ),
        )
    }
}
