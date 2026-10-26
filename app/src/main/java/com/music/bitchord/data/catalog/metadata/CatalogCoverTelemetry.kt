package com.music.bitchord.data.catalog.metadata

/**
 * Identity-free telemetry for upcoming cold/warm Galaxy A54 coverage.
 * Stores only bounded aggregate latencies and outcome counters: no MBIDs,
 * titles, URLs, queries, device identifiers or listener information.
 */
internal class CatalogCoverTelemetry(private val maxSamples: Int = 256) {
    init { require(maxSamples in 1..1024) }

    internal data class Snapshot(
        val positiveCacheHits: Int,
        val negativeCacheHits: Int,
        val coalescedRequests: Int,
        /** CAA CDN URLs handed to Coil; not measured image successes. */
        val directFrontCandidates: Int,
        val networkRequests: Int,
        val successfulNetworkRequests: Int,
        val networkNotFound: Int,
        val transientFailures: Int,
        val latencySampleCount: Int,
        val p50NetworkMs: Long?,
        val p95NetworkMs: Long?,
    )

    private var cached = 0
    private var negative = 0
    private var coalesced = 0
    private var direct = 0
    private var network = 0
    private var successful = 0
    private var missing = 0
    private var transient = 0
    private val durations = ArrayDeque<Long>()

    /** Start a clean anonymous metric window for each explicit capture. */
    @Synchronized fun reset() {
        cached = 0
        negative = 0
        coalesced = 0
        direct = 0
        network = 0
        successful = 0
        missing = 0
        transient = 0
        durations.clear()
    }

    @Synchronized fun recordCacheHit() { cached++ }
    @Synchronized fun recordNegativeCacheHit() { negative++ }
    @Synchronized fun recordJoinedRequest() { coalesced++ }
    @Synchronized fun recordDirectFrontCandidate() { direct++ }
    @Synchronized fun recordNetwork(status: Int, durationMs: Long) {
        network++
        when (status) {
            200 -> successful++
            404 -> missing++
            else -> transient++
        }
        if (durations.size == maxSamples) durations.removeFirst()
        durations.addLast(durationMs.coerceAtLeast(0))
    }

    @Synchronized fun snapshot(): Snapshot {
        val sorted = durations.sorted()
        fun quantile(percent: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = ((percent * sorted.size + 99) / 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
        return Snapshot(cached, negative, coalesced, direct, network, successful,
            missing, transient, sorted.size, quantile(50), quantile(95))
    }

    companion object {
        /** Shared only as an in-process diagnostic; it does not persist identity. */
        val process = CatalogCoverTelemetry()
    }
}
