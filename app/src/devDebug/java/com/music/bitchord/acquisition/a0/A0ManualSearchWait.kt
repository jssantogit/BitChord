package com.music.bitchord.acquisition.a0

import java.util.concurrent.BlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Lifecycle shutdown interrupts blocking waits. An interrupted wait is
 * cancellation, not a network failure or an uncaught Android worker crash. */
internal object A0ManualSearchWait {
    fun <T> poll(queue:BlockingQueue<T>,duration:Long,unit:TimeUnit):T? =
        try { queue.poll(duration,unit) }
        catch(_:InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Manual search interrupted")
        }
}
