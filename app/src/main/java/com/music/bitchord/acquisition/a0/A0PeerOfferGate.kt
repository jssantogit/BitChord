package com.music.bitchord.acquisition.a0

/**
 * Per-search bounded deduplication of server-guided P offers. A peer's exact
 * ticket must never occupy worker slots twice; different tickets may still be
 * valid. The map is memory-only and never appears in diagnostic exports.
 */
internal class A0PeerOfferGate(private val capacity:Int=160) {
    init { require(capacity in 8..256) }
    private val seen=HashSet<Pair<String,Int>>()
    private val maxTicketsPerPeer=8
    var duplicateCount:Int=0
        private set
    var capacityDropped:Int=0
        private set
    var perPeerDropped:Int=0
        private set
    val acceptedCount:Int get()=seen.size

    /** A ticket rejected by the bounded worker queue was never processed.
     * Releasing that reservation keeps both capacity and retry accounting
     * truthful; accepted tasks must never call this rollback.
     */
    @Synchronized
    fun releaseUnsubmitted(username:String,token:Int):Boolean =
        seen.remove(username to token)

    @Synchronized
    fun accept(username:String,token:Int):Boolean {
        val key=username to token
        if(key in seen) {
            duplicateCount++
            return false
        }
        if(seen.size>=capacity) {
            capacityDropped++
            return false
        }
        // Server-side floods of distinct tokens from one untrusted peer
        // must not starve other peers within our bounded search window.
        // Different tickets remain valid up to this conservative per-peer cap.
        if(seen.count { it.first==username }>=maxTicketsPerPeer) {
            perPeerDropped++
            return false
        }
        seen.add(key)
        return true
    }
}
