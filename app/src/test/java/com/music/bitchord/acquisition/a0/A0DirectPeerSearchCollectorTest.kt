package com.music.bitchord.acquisition.a0

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** A slow inbound P peer must not delay parsing its 11 faster neighbours. */
class A0DirectPeerSearchCollectorTest {
    @Test(timeout = 5000)
    fun twelveInboundPeersAreDispatchedBeforeTheFirstOneResponds() {
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(12)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val collector = A0DirectPeerSearchCollector { _ ->
            val n = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, n) }
            started.countDown()
            unblock.await(2, TimeUnit.SECONDS)
            active.decrementAndGet()
        }
        try {
            repeat(12) { assertTrue(collector.offer(Socket())) }
            assertTrue("All peers should begin while the first is stalled",
                started.await(2, TimeUnit.SECONDS))
            assertTrue(peak.get() >= 12)
        } finally {
            unblock.countDown()
            collector.closeSockets()
            assertTrue(collector.stop(2000))
        }
    }

    @Test(timeout = 5000)
    fun saturatedCollectorRejectsAndClosesSocketsWithoutLeaking() {
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(12)
        val collector = A0DirectPeerSearchCollector { _ ->
            started.countDown()
            unblock.await(2, TimeUnit.SECONDS)
        }
        try {
            repeat(12) { assertTrue(collector.offer(Socket())) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(128) { assertTrue(collector.offer(Socket())) }
            val rejected = Socket()
            assertFalse(collector.offer(rejected))
            assertTrue("Rejected TCP sockets must be released", rejected.isClosed)
        } finally {
            unblock.countDown()
            collector.closeSockets()
            assertTrue(collector.stop(2000))
        }
        val afterStop = Socket()
        assertFalse(collector.offer(afterStop))
        assertTrue(afterStop.isClosed)
    }
}
