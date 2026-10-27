package com.music.bitchord.acquisition.a0

/**
 * Per-search bounded deduplication of server-guided P offers.
 * Peer names/tickets stay only in memory; diagnostics expose counts only.
 */
internal class A0PeerOfferGate(private val capacity:Int=160) {
    init { require(capacity in 8..256) }
    private val seen=HashSet<Pair<String,Int>>()
    // Constant-time per-peer fairness under offer storms. Keep reservations
    // consistent when a bounded worker queue refuses an unsubmitted ticket.
    private val ticketsByPeer=HashMap<String,Int>()
    private val maxTicketsPerPeer=8
    var duplicateCount:Int=0
        private set
    var capacityDropped:Int=0
        private set
    var perPeerDropped:Int=0
        private set
    val acceptedCount:Int get()=seen.size

    @Synchronized
    fun releaseUnsubmitted(username:String,token:Int):Boolean {
        if(!seen.remove(username to token))return false
        val count=checkNotNull(ticketsByPeer[username])
        if(count==1)ticketsByPeer.remove(username)
        else ticketsByPeer[username]=count-1
        return true
    }

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
        val peerCount=ticketsByPeer[username] ?: 0
        if(peerCount>=maxTicketsPerPeer) {
            perPeerDropped++
            return false
        }
        seen.add(key)
        ticketsByPeer[username]=peerCount+1
        return true
    }
}
