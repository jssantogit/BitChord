package com.music.bitchord.acquisition.a0

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray

/** Read-only session correlation of the original search and two variations.
 * A registered ticket can only be added by the single search driver. Peers
 * cannot add tickets; only accepted, individually validated replies count.
 */
internal class A0SearchTicketRegistry(initial:Int) {
    private val tickets=ConcurrentHashMap<Int,Int>()
    private val replies=AtomicIntegerArray(3)

    init { require(initial>0); tickets[initial]=0 }

    val accepted:Set<Int> get()=tickets.keys

    @Synchronized fun register(token:Int):Boolean {
        if(token<=0 || tickets.size>=3 || tickets.containsKey(token))return false
        tickets[token]=tickets.size
        return true
    }

    fun creditValidated(token:Int) {
        val ordinal=tickets[token] ?: return
        replies.incrementAndGet(ordinal)
    }

    /** An aggregate, not tokens or peer identities. */
    fun responseCounts():List<Int>=(0..2).map { replies.get(it).coerceIn(0,160) }
}
