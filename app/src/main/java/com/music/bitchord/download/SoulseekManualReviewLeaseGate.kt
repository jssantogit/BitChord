package com.music.bitchord.download

import java.util.UUID

/**
 * One-shot, process-local ownership of an A3 manual recovery worker.
 *
 * The internal Activity may be recreated with the original Intent. Matching
 * the batch payload alone must NEVER grant a second Activity ownership of an
 * existing peer transfer, or let it close the first Activity's lease.
 *
 * The token authorizes no download or publication. Every candidate still
 * requires explicit human selection and the usual durable A3 commit.
 */
internal class SoulseekManualReviewLeaseGate {
    private data class Lease(val token:String,var claimed:Boolean=false)
    private val leases=LinkedHashMap<String,Lease>()

    val hasOpen:Boolean @Synchronized get()=leases.isNotEmpty()

    @Synchronized fun reserve(sessionId:String):String? {
        if(sessionId.isBlank() || leases.isNotEmpty())return null
        val token=UUID.randomUUID().toString()
        leases[sessionId]=Lease(token)
        return token
    }

    /** Called only after the Activity's original slot payload is validated. */
    @Synchronized fun claim(sessionId:String,token:String):Boolean {
        val lease=leases[sessionId] ?: return false
        if(lease.token!=token || lease.claimed)return false
        lease.claimed=true
        return true
    }

    /** A different, recreated or stale Activity cannot release the worker. */
    @Synchronized fun close(sessionId:String,token:String):Boolean {
        if(leases[sessionId]?.token!=token)return false
        leases.remove(sessionId)
        return true
    }

    override fun toString()="SoulseekManualReviewLeaseGate(redacted)"
}
