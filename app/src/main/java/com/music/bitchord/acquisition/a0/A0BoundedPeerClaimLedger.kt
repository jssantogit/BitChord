package com.music.bitchord.acquisition.a0

/**
 * Bounded per-search dedup of untrusted peer paths.
 *
 * Clearing the entire set at capacity lets thousands of unrelated files
 * resurrect all earlier identities as apparently unseen candidates.
 * Evict exactly the oldest entry instead; retain recent duplicate protection
 * and never export names or paths through exceptions/diagnostics.
 */
internal class A0BoundedPeerClaimLedger(private val capacity:Int=8192) {
    init { require(capacity in 8..8192) }
    private val recent=LinkedHashSet<Pair<String,String>>()

    @Synchronized
    fun admit(peer:String,path:String):Boolean {
        val claim=peer to path
        if(claim in recent)return false
        if(recent.size>=capacity) {
            val iterator=recent.iterator()
            if(iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        recent.add(claim)
        return true
    }

    val retainedCount:Int @Synchronized get()=recent.size
    override fun toString()="A0BoundedPeerClaimLedger(redacted)"
}
