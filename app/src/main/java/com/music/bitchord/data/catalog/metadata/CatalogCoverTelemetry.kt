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
        val imageRenderSuccesses: Int,
        val imageRenderFailures: Int,
        val imageLoadSampleCount: Int,
        val p50ImageLoadMs: Long?,
        val p95ImageLoadMs: Long?,
        val coilMemoryHits: Int,
        val coilDiskHits: Int,
        val coilNetworkFetches: Int,
        val coilOtherSources: Int,
        val verifiedGroupCachedBatchReads: Int,
        val verifiedGroupCachedRowsReused: Int,
        val recoveredCoalescedOwnerCancellations: Int,
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
    private var loadedImages = 0
    private var failedImages = 0
    private val imageDurations = ArrayDeque<Long>()
    private var imageMemory = 0
    private var imageDisk = 0
    private var imageNetwork = 0
    private var imageOther = 0
    private var groupCacheBatchReads = 0
    private var groupCacheRows = 0
    private var recoveredSharedOwners = 0

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
        loadedImages = 0
        failedImages = 0
        imageDurations.clear()
        imageMemory = 0
        imageDisk = 0
        imageNetwork = 0
        imageOther = 0
        groupCacheBatchReads = 0
        groupCacheRows = 0
        recoveredSharedOwners = 0
    }

    /** No MBID/album is captured: only counts of successfully verified fronts. */
    @Synchronized fun recordVerifiedGroupCachedBatch(reusedRows: Int) {
        groupCacheBatchReads++
        groupCacheRows += reusedRows.coerceAtLeast(0)
    }

    @Synchronized fun recordRecoveredCoalescedOwnerCancellation() {
        recoveredSharedOwners++
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

    /**
     * Coil finished loading a CAA bitmap (or reported an error). This is
     * measured at the actual Android image consumer, not inferred from the
     * much faster MB flag/direct URL nomination.
     */
    @Synchronized fun recordImageResult(
        success: Boolean,
        durationMs: Long,
        source: String? = null,
    ) {
        if (success) {
            loadedImages++
            // Only a four-way aggregate, never the request URL, image key,
            // album or source metadata, is retained in diagnostics.
            when (source?.uppercase(java.util.Locale.ROOT)) {
                "MEMORY", "MEMORY_CACHE" -> imageMemory++
                "DISK" -> imageDisk++
                "NETWORK" -> imageNetwork++
                else -> imageOther++
            }
        } else failedImages++
        if (imageDurations.size == maxSamples) imageDurations.removeFirst()
        imageDurations.addLast(durationMs.coerceAtLeast(0))
    }

    @Synchronized fun snapshot(): Snapshot {
        val networkSorted = durations.sorted()
        val imagesSorted = imageDurations.sorted()
        fun quantile(sorted: List<Long>, percent: Int): Long? {
            if (sorted.isEmpty()) return null
            val rank = ((percent * sorted.size + 99) / 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
        return Snapshot(cached, negative, coalesced, direct, network, successful,
            missing, transient, networkSorted.size,
            quantile(networkSorted, 50), quantile(networkSorted, 95),
            loadedImages, failedImages, imagesSorted.size,
            quantile(imagesSorted, 50), quantile(imagesSorted, 95),
            imageMemory, imageDisk, imageNetwork, imageOther,
            groupCacheBatchReads, groupCacheRows, recoveredSharedOwners)
    }

    companion object {
        /** Shared only as an in-process diagnostic; it does not persist identity. */
        val process = CatalogCoverTelemetry()
    }
}

/**
 * CAA images can resolve to per-shard Archive.org CDN hosts. The previous
 * diagnostic observer counted only s3.us.archive.org and silently missed
 * other valid shards, biasing reported Coil image timing.
 */
internal fun isCoverArchiveImageHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val uri = java.net.URI(url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT)
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            (host == "coverartarchive.org" || host == "archive.org" ||
                host?.endsWith(".archive.org") == true)
    } catch (_: Exception) { false }
}
