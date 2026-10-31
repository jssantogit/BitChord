package com.music.bitchord.download

import java.util.Locale

/**
 * Session-local fence against duplicate *automatic* acquisitions.
 *
 * A UI report can be cleared, evicted or dismissed while its source audio
 * remains published in A3. None of those UI operations is permission to start
 * a second P/F for the same canonical slot. We conservatively match by both
 * source recording ID and artist/title + release hint: different providers
 * may use different IDs for identical audio. This is NOT a durable media
 * catalog deduplicator or proof of audio identity.
 *
 * Entries are deliberately NOT evicted. Once the bounded capacity is reached,
 * automatic requests fail closed until a process restart, where the durable
 * A3/local-library preflight must still prevent physical duplicates.
 */
internal class SoulseekAutoAttemptGuard(
    private val maxSlots:Int=4096,
) {
    init { require(maxSlots in 1..65536) }
    private data class Fingerprint(val query:String,val album:String?)
    private val slots=LinkedHashSet<String>()
    private val hints=LinkedHashSet<Fingerprint>()
    private val reservations=LinkedHashMap<String,Fingerprint>()
    val slotCount:Int get()=reservations.size

    private fun fingerprint(e:SoulseekBatchPlan.Entry)=Fingerprint(
        e.query.trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," "),
        e.album?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+")," "),
    )

    enum class Refusal {
        INVALID_BATCH, DUPLICATE_IN_REQUEST, CAPACITY_REACHED,
        SOURCE_ID_PREVIOUSLY_ATTEMPTED, RELEASE_HINT_PREVIOUSLY_ATTEMPTED,
    }
    /** Read-only classification, called under Downloads.lock. No identifiers
     * are persisted or logged; returning null does not reserve anything. */
    fun refusal(entries:List<SoulseekBatchPlan.Entry>):Refusal? {
        if(entries.size !in 1..SoulseekBatchPlan.MAX_TRACKS ||
            entries.any { it.trackId.isBlank() || it.query.isBlank() })
            return Refusal.INVALID_BATCH
        if(entries.map { it.trackId }.distinct().size!=entries.size)
            return Refusal.DUPLICATE_IN_REQUEST
        if(slots.size+entries.size>maxSlots)return Refusal.CAPACITY_REACHED
        val newHints=entries.map(::fingerprint)
        if(newHints.distinct().size!=newHints.size)
            return Refusal.DUPLICATE_IN_REQUEST
        if(entries.any { it.trackId in slots })
            return Refusal.SOURCE_ID_PREVIOUSLY_ATTEMPTED
        if(newHints.any { it in hints })
            return Refusal.RELEASE_HINT_PREVIOUSLY_ATTEMPTED
        return null
    }

    /** Caller must hold Downloads.lock; false never modifies the fence. */
    fun reserve(entries:List<SoulseekBatchPlan.Entry>):Boolean {
        if(refusal(entries)!=null)return false
        val newHints=entries.map(::fingerprint)
        entries.zip(newHints).forEach { (entry,hint) ->
            slots.add(entry.trackId)
            hints.add(hint)
            reservations[entry.trackId]=hint
        }
        return true
    }

    /** Roll back only when starting the foreground service was refused, before
     * any worker had the chance to accept the request. Never rollback after
     * a cancellation, timeout, manual review or A3 publication attempt.
     */
    fun rollback(entries:List<SoulseekBatchPlan.Entry>) {
        entries.forEach { entry ->
            val hint=fingerprint(entry)
            if(reservations[entry.trackId]==hint) {
                reservations.remove(entry.trackId)
                slots.remove(entry.trackId)
                hints.remove(hint)
            }
        }
    }

    override fun toString()="SoulseekAutoAttemptGuard(redacted)"
}
