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
    private val publicFiles=AtomicIntegerArray(3)

    init { require(initial>0); tickets[initial]=0 }

    val accepted:Set<Int> get()=tickets.keys

    @Synchronized fun register(token:Int):Boolean {
        if(token<=0 || tickets.size>=3 || tickets.containsKey(token))return false
        tickets[token]=tickets.size
        return true
    }

    /** Internal query order for report assembly; never emit random tokens. */
    @Synchronized fun tokenAt(index:Int):Int? =
        if(index !in 0..2)null
        else tickets.entries.firstOrNull { it.value==index }?.key

    fun creditValidated(token:Int,publicFiles:Int=0) {
        val ordinal=tickets[token] ?: return
        replies.incrementAndGet(ordinal)
        // Decoder limits a reply to 4096 files. Count only validated tickets,
        // without filenames, peers or transfer/candidate authority.
        this.publicFiles.addAndGet(ordinal,publicFiles.coerceIn(0,4096))
    }

    /** An aggregate, not tokens or peer identities. */
    fun responseCounts(): List<Int> = (0..2).map { replies.get(it).coerceIn(0,160) }

    /** Counts raw public file claims, NOT eligible/strong candidates. */
    fun publicFileCounts(): List<Int> = (0..2).map {
        publicFiles.get(it).coerceIn(0,100_000)
    }
}
