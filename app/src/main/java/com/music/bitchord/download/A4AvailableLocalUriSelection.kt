package com.music.bitchord.download

/**
 * Read-only bridge from finished acquisition jobs to the existing LOCAL music
 * library. A Soulseek origin never becomes a new library/source category.
 *
 * Probe failures (revoked SAF grants, offline storage, media scanner latency)
 * temporarily omit the URI; they MUST NOT delete its durable A3 record.
 */
internal object A4AvailableLocalUriSelection {
    fun choose(
        entries:List<A3AcquisitionLedger.Entry>,
        alreadyScanned:Set<String>,
        canRead:(String)->Boolean
    ):List<String> {
        val seen=alreadyScanned.toMutableSet()
        return entries.asSequence()
            .filter { it.phase==A3AcquisitionLedger.Phase.AVAILABLE }
            .mapNotNull { it.contentUri }
            .filter { it.startsWith("content://") }
            .filter { it !in seen }
            .filter { uri -> runCatching { canRead(uri) }.getOrDefault(false) }
            .filter { seen.add(it) }
            .toList()
    }
}
