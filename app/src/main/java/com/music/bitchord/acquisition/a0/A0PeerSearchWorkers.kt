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
    private val action:(()->Unit)->Unit = { it() }
) {
    private val closed=AtomicBoolean(false)
    // At most 36 offers are processed per live search. Eight sockets can
    // drain that burst within the existing short discovery window even when
    // peers take seconds to time out; the queue is still strictly bounded.
    private val pool=ThreadPoolExecutor(
        8,8,0L,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(32),
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
