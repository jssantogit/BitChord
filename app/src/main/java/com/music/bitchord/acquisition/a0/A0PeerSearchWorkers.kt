package com.music.bitchord.acquisition.a0

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded peer-only P sockets. Never consumes the authenticated server socket.
 * Occupancy counters are local aggregates: no peer IDs, tokens, or paths.
 */
internal class A0PeerSearchWorkers(
    private val action:(()->Unit)->Unit = { it() },
    queueCapacity:Int = 32,
    parallelism:Int = 8,
) {
    init {
        require(queueCapacity in 8..128)
        require(parallelism in 4..12)
    }

    internal data class Stats(
        val accepted:Int,
        val rejected:Int,
        val started:Int,
        val completed:Int,
        val peakActive:Int,
        val peakQueued:Int,
        val activeNow:Int,
        val queuedNow:Int,
    )

    private val closed=AtomicBoolean(false)
    private val accepted=AtomicInteger()
    private val rejected=AtomicInteger()
    private val started=AtomicInteger()
    private val completed=AtomicInteger()
    private val active=AtomicInteger()
    private val peakActive=AtomicInteger()
    private val peakQueued=AtomicInteger()
    private val pool=ThreadPoolExecutor(
        parallelism,parallelism,0L,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(queueCapacity),
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(job:()->Unit):Boolean {
        if(closed.get())return false
        return try {
            pool.execute {
                val current=active.incrementAndGet()
                started.incrementAndGet()
                peakActive.updateAndGet { maxOf(it,current) }
                try {
                    if(!closed.get()) action(job)
                } finally {
                    active.decrementAndGet()
                    completed.incrementAndGet()
                }
            }
            accepted.incrementAndGet()
            peakQueued.updateAndGet { maxOf(it,pool.queue.size) }
            true
        } catch (_:RejectedExecutionException) {
            rejected.incrementAndGet()
            false
        }
    }

    fun snapshot():Stats = Stats(
        accepted=accepted.get(),
        rejected=rejected.get(),
        started=started.get(),
        completed=completed.get(),
        peakActive=peakActive.get(),
        peakQueued=peakQueued.get(),
        activeNow=active.get(),
        queuedNow=pool.queue.size,
    )

    /** Never interrupts the authenticated server reader; only P workers. */
    fun stop(timeoutMillis:Long):Boolean {
        require(timeoutMillis in 100..8000)
        closed.set(true)
        pool.shutdown()
        val first=try {
            pool.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS)
        } catch(e:InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if(first)return true
        pool.shutdownNow()
        return try {
            pool.awaitTermination(500,TimeUnit.MILLISECONDS)
        }catch(e:InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun cancel() {
        closed.set(true)
        pool.shutdownNow()
    }
}
