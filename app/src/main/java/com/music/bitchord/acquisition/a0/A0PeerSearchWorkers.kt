package com.music.bitchord.acquisition.a0

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded pool for *peer-only* P sockets. It must never read or write the
 * authenticated Soulseek server socket (single reader, P/F handoff preserved).
 * Slow peers are independent, unlike A0's previous blocking offer loop.
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
    private val closed=AtomicBoolean(false)
    // Parallel reads remain bounded, with a short queue and deterministic
    // shutdown. Configurable only for the guided A0 discovery session.
    private val pool=ThreadPoolExecutor(
        parallelism,parallelism,0L,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(queueCapacity),
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(job:()->Unit):Boolean {
        if(closed.get()) return false
        return try {
            pool.execute {
                if(!closed.get()) action(job)
            }
            true
        }catch (_:RejectedExecutionException) {false}
    }

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
