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
        val coilSuccess: Int,
        val coilFailure: Int,
        val coilCancelled: Int,
        val coilMemory: Int,
        val coilDisk: Int,
        val coilNetwork: Int,
        val coilP95Ms: Long?,
    )

    private val attempted = mutableMapOf(600 to 0, 720 to 0, 1024 to 0)
    private val verified = mutableMapOf(600 to 0, 720 to 0, 1024 to 0)
    private var cachePositive = 0
    private var cacheNegative = 0
    private val durations = ArrayDeque<Long>()
    private val coilDurations = ArrayDeque<Long>()
    private var coilSuccess = 0
    private var coilFailure = 0
    private var coilCancelled = 0
    private var coilMemory = 0
    private var coilDisk = 0
    private var coilNetwork = 0

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

    @Synchronized fun recordCoilImageResult(
        success: Boolean,
        durationMs: Long,
        source: String?,
    ) {
        if (success) {
            coilSuccess++
            when (source?.uppercase(java.util.Locale.ROOT)) {
                "MEMORY", "MEMORY_CACHE" -> coilMemory++
                "DISK" -> coilDisk++
                "NETWORK" -> coilNetwork++
            }
        } else coilFailure++
        if (coilDurations.size == maxSamples) coilDurations.removeFirst()
        coilDurations.addLast(durationMs.coerceAtLeast(0L))
    }

    @Synchronized fun recordCoilImageCancellation() { coilCancelled++ }

    @Synchronized fun snapshot(): Snapshot {
        val sorted = durations.sorted()
        val coilSorted = coilDurations.sorted()
        fun quantile(percent: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = ((percent * sorted.size + 99) / 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
        val coilP95 = if (coilSorted.isEmpty()) null else
            coilSorted[((95 * coilSorted.size + 99) / 100)
                .coerceIn(1, coilSorted.size) - 1]
        return Snapshot(
            attempted.getValue(600), verified.getValue(600),
            attempted.getValue(720), verified.getValue(720),
            attempted.getValue(1024), verified.getValue(1024),
            cachePositive, cacheNegative, sorted.size, quantile(50), quantile(95),
            coilSuccess, coilFailure, coilCancelled, coilMemory, coilDisk,
            coilNetwork, coilP95,
        )
    }

    @Synchronized fun reset() {
        attempted.keys.toList().forEach { attempted[it] = 0; verified[it] = 0 }
        cachePositive = 0
        cacheNegative = 0
        durations.clear()
        coilDurations.clear()
        coilSuccess = 0
        coilFailure = 0
        coilCancelled = 0
        coilMemory = 0
        coilDisk = 0
        coilNetwork = 0
    }

    companion object {
        val process = AppleArtworkProbeTelemetry()
    }
}
