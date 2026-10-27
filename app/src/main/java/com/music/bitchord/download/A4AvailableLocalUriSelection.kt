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
        alreadyIdentities:Set<String> = emptySet(),
        identityOf:(String)->String = { it },
        aliasesOf:(String)->Set<String> = { emptySet() },
        canRead:(String)->Boolean
    ):List<String> {
        // An exact physical file may have several aliases: media ID,
        // MediaStore DATA path, or a SAF URI. Match ANY known physical alias.
        // Names, artists and durations are never identity evidence.
        fun identities(raw:String):Set<String> = setOf(identityOf(raw)) + aliasesOf(raw)
        val seen=alreadyIdentities.toMutableSet()
        alreadyScanned.forEach { seen.addAll(identities(it)) }
        return entries.asSequence()
            .filter { it.phase==A3AcquisitionLedger.Phase.AVAILABLE }
            .mapNotNull { it.contentUri }
            .filter { it.startsWith("content://") }
            .filter { uri -> identities(uri).none { it in seen } }
            .filter { uri -> runCatching { canRead(uri) }.getOrDefault(false) }
            .filter { uri ->
                val aliases=identities(uri)
                if(aliases.any { it in seen })false
                else { seen.addAll(aliases);true }
            }
            .toList()
    }
}
