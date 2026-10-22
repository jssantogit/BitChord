package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** A joined reader has relinquished ownership without interrupting server TCP. */
class A0SearchReaderHandoffTest {
    @Test(timeout=4000)
    fun stopsReaderAtFrameBoundaryWithoutThreadInterrupt() {
        val pool=Executors.newFixedThreadPool(2)
        val signals=ArrayBlockingQueue<Boolean>(2)
        val wasInterrupted=AtomicBoolean(false)
        try {
            repeat(2) {
                pool.execute {
                    signals.take()
                    wasInterrupted.set(
                        wasInterrupted.get() || Thread.currentThread().isInterrupted
                    )
                }
            }
            A0SearchReaderHandoff.stop(pool, {
                repeat(2){signals.offer(true)}
            },2000)
            assertTrue(pool.isTerminated)
            assertFalse("Unexpected interrupt may tear down authenticated TCP",wasInterrupted.get())
        }finally{
            pool.shutdownNow()
        }
    }

    @Test(timeout=4000)
    fun blockedReaderDeniesHandoffInsteadOfPretendingSocketIsUnowned() {
        val pool=Executors.newSingleThreadExecutor()
        val workerStarted=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        try {
            pool.execute {
                workerStarted.countDown()
                release.await(3,TimeUnit.SECONDS)
            }
            assertTrue(workerStarted.await(1,TimeUnit.SECONDS))
            try {
                A0SearchReaderHandoff.stop(pool,{},120)
                fail("No P/F handoff while a reader still owns the authenticated connection")
            }catch (_:IOException){}
        }finally{
            release.countDown()
            pool.shutdownNow()
        }
    }
}
