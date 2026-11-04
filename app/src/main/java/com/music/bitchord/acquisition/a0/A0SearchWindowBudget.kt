package com.music.bitchord.acquisition.a0

/**
 * Conservative in-process sliding window for the Soulseek server's
 * empirically observed search-rate protections. A session reserves up to
 * three query slots BEFORE login, but can start with one available slot;
 * only actually sent queries are charged.
 *
 * This is not a server-provided quota. The 27/220s default leaves headroom
 * below the 34/220s empirical cap documented by third-party Soulseek clients.
 * No user identity, query, peer or account is persisted.
 */
internal class A0SearchWindowBudget(
    private val maxRequests:Int=27,
    private val windowMillis:Long=220_000L,
) {
    init {
        require(maxRequests in 3..128)
        require(windowMillis in 1_000L..3_600_000L)
    }

    private data class Entry(val leaseId:Long, var timestamp:Long, var committed:Boolean=false)
    private val entries=ArrayList<Entry>()
    private var nextLeaseId=1L

    inner class Lease internal constructor(
        private val leaseId:Long,
        private val maxSlots:Int,
        private var allocated:Int,
    ):AutoCloseable {
        private var closed=false
        /** A fallback can use capacity that expires during the 18s session.
         * The same lease still permits at most maxSlots total reservations.
         */
        @Synchronized fun reserveOneMore(nowMillis:Long):Boolean {
            if(closed || allocated>=maxSlots)return false
            if(!allocateSlot(leaseId,nowMillis))return false
            allocated++
            return true
        }
        /** Unsent reservations belonging to this live session. */
        @Synchronized fun remaining():Int =
            if(closed) 0 else unusedSlots(leaseId)
        @Synchronized fun commit(nowMillis:Long):Boolean {
            if(closed)return false
            return commitSlot(leaseId,nowMillis)
        }
        @Synchronized override fun close() {
            if(closed)return
            closed=true
            releaseUnused(leaseId)
        }
    }

    @Synchronized private fun prune(nowMillis:Long) {
        entries.removeAll { it.timestamp <= nowMillis-windowMillis }
    }

    @Synchronized fun available(nowMillis:Long):Int {
        prune(nowMillis)
        return maxRequests-entries.size
    }

    @Synchronized fun waitMillis(count:Int,nowMillis:Long):Long {
        require(count in 1..maxRequests)
        prune(nowMillis)
        val missing=count-(maxRequests-entries.size)
        if(missing<=0)return 0
        val expireAt=entries.map { it.timestamp+windowMillis }.sorted()
        return (expireAt[missing-1]-nowMillis).coerceAtLeast(1L)
    }

    @Synchronized fun tryAcquire(count:Int,nowMillis:Long):Lease? {
        require(count in 1..maxRequests)
        prune(nowMillis)
        if(maxRequests-entries.size<count)return null
        val id=nextLeaseId++
        repeat(count) { entries.add(Entry(id,nowMillis)) }
        return Lease(id,count,count)
    }

    @Synchronized private fun unusedSlots(leaseId:Long):Int =
        entries.count { it.leaseId==leaseId && !it.committed }

    /** Reserve at least one search, without waiting for optional fallbacks.
     * This still atomically counts every reserved slot toward the same cap.
     */
    @Synchronized fun tryAcquireUpTo(count:Int,nowMillis:Long):Lease? {
        require(count in 1..maxRequests)
        prune(nowMillis)
        val reserved=minOf(count,maxRequests-entries.size)
        if(reserved<=0)return null
        val id=nextLeaseId++
        repeat(reserved) { entries.add(Entry(id,nowMillis)) }
        return Lease(id,count,reserved)
    }

    @Synchronized private fun allocateSlot(leaseId:Long,nowMillis:Long):Boolean {
        prune(nowMillis)
        if(entries.size>=maxRequests)return false
        entries.add(Entry(leaseId,nowMillis))
        return true
    }

    @Synchronized private fun commitSlot(leaseId:Long,nowMillis:Long):Boolean {
        val slot=entries.firstOrNull { it.leaseId==leaseId && !it.committed }
            ?:return false
        // Charged at the actual send, never before. If the transport fails
        // after this step, keep the conservative charge until expiry.
        slot.committed=true
        slot.timestamp=nowMillis
        return true
    }

    @Synchronized private fun releaseUnused(leaseId:Long) {
        entries.removeAll { it.leaseId==leaseId && !it.committed }
    }
}

/** One process-wide budget across albums and successive automatic batches. */
internal object A0GlobalSearchWindowBudget {
    val shared=A0SearchWindowBudget()
}
