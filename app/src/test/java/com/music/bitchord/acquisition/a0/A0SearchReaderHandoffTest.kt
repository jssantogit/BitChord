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

    @Test
    fun publishedSearchResultKeepsReaderClosedAfterInboxIsDrained() {
        val gate=A0SearchReaderGate()
        val inbox=ArrayBlockingQueue<String>(1)
        assertTrue(gate.isOpen())
        assertTrue(gate.publish(inbox,"valid reply"))
        assertEquals("valid reply",inbox.poll())
        assertFalse("Consumption must not re-enable the server reader",gate.isOpen())
        assertFalse("A late offer must not publish another result",gate.publish(inbox,"late"))
        assertNull(inbox.poll())
    }

    @Test
    fun stoppingTimedOutSearchRejectsLatePeerPublication() {
        val gate=A0SearchReaderGate()
        val inbox=ArrayBlockingQueue<String>(1)
        gate.stop()
        assertFalse(gate.isOpen())
        assertFalse(gate.publish(inbox,"late response"))
        assertNull(inbox.poll())
    }

}
