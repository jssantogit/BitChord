package com.music.bitchord.acquisition.a0

import java.util.concurrent.atomic.AtomicInteger

/**
 * Anonymous timing distribution of rejected indirect P worker submissions.
 * This is observation only: neither the window, sockets nor admission limits
 * change. Counts are safe for structured diagnostics; no peer/ticket identity.
 */
internal class A0PeerQueueRejectionTimeline {
    data class Summary(
        val before6:Int,
        val sixTo12:Int,
        val after12:Int,
    ) {
        val total:Int get()=before6+sixTo12+after12
    }

    private val before6=AtomicInteger()
    private val sixTo12=AtomicInteger()
    private val after12=AtomicInteger()

    fun rejectedAt(elapsedMillis:Long) {
        when {
            elapsedMillis<0L -> return
            elapsedMillis<6000L -> before6.incrementAndGet()
            elapsedMillis<12000L -> sixTo12.incrementAndGet()
            else -> after12.incrementAndGet()
        }
    }

    fun snapshot():Summary=Summary(
        before6.get(),sixTo12.get(),after12.get())
}
