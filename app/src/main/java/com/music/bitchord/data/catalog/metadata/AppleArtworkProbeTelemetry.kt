package com.music.bitchord.data.catalog.metadata

/**
 * Anonymous artwork-delivery evidence. Each counter describes a CDN dimension
 * probe, NOT a decoded Coil bitmap or a certified MusicBrainz edition.
 *
 * Absolutely no URL, title, MBID, Apple product ID or device/user identifier
 * is retained. The only dimensions recorded are 600/720/1024 pixels.
 */
internal class AppleArtworkProbeTelemetry(private val maxSamples: Int = 128) {
    init { require(maxSamples in 1..512) }

    internal data class Snapshot(
        val attempts600: Int,
        val verified600: Int,
        val attempts720: Int,
        val verified720: Int,
        val attempts1024: Int,
        val verified1024: Int,
        val positiveCacheHits: Int,
        val negativeCacheHits: Int,
        val networkSampleCount: Int,
        val p50NetworkMs: Long?,
        val p95NetworkMs: Long?,
    )

    private val attempted = mutableMapOf(600 to 0, 720 to 0, 1024 to 0)
    private val verified = mutableMapOf(600 to 0, 720 to 0, 1024 to 0)
    private var cachePositive = 0
    private var cacheNegative = 0
    private val durations = ArrayDeque<Long>()

    @Synchronized fun recordNetwork(size: Int, success: Boolean, durationMs: Long) {
        if (size !in attempted) return
        attempted[size] = attempted.getValue(size) + 1
        if (success) verified[size] = verified.getValue(size) + 1
        if (durations.size == maxSamples) durations.removeFirst()
        durations.addLast(durationMs.coerceAtLeast(0))
    }

    @Synchronized fun recordCacheHit(valid: Boolean) {
        if (valid) cachePositive++ else cacheNegative++
    }

    @Synchronized fun snapshot(): Snapshot {
        val sorted = durations.sorted()
        fun quantile(percent: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = ((percent * sorted.size + 99) / 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
        return Snapshot(
            attempted.getValue(600), verified.getValue(600),
            attempted.getValue(720), verified.getValue(720),
            attempted.getValue(1024), verified.getValue(1024),
            cachePositive, cacheNegative, sorted.size, quantile(50), quantile(95),
        )
    }

    @Synchronized fun reset() {
        attempted.keys.toList().forEach { attempted[it] = 0; verified[it] = 0 }
        cachePositive = 0
        cacheNegative = 0
        durations.clear()
    }

    companion object {
        val process = AppleArtworkProbeTelemetry()
    }
}
