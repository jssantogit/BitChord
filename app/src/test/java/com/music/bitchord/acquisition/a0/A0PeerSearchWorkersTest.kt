package com.music.bitchord.acquisition.a0

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Peer P handshakes may block, but must never stall server offer reads. */
class A0PeerSearchWorkersTest {
    @Test(timeout=5000)
    fun independentPeerWorkIsBoundedAndConcurrent() {
        val gate=CountDownLatch(1)
        val started=CountDownLatch(3)
        val active=AtomicInteger()
        val peak=AtomicInteger()
        val workers=A0PeerSearchWorkers({ task ->
            val now=active.incrementAndGet()
            peak.updateAndGet { maxOf(it,now) }
            started.countDown()
            gate.await(2,TimeUnit.SECONDS)
            active.decrementAndGet()
        })
        try {
            repeat(3) { assertTrue(workers.submit { }) }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertTrue("Blocked peers must run concurrently",peak.get()>=2)
        } finally {
            gate.countDown()
            assertTrue(workers.stop(2000))
        }
    }

    @Test(timeout=5000)
    fun queueRejectsExcessiveOffersAndCleanupIsTerminal() {
        val gate=CountDownLatch(1)
        val started=CountDownLatch(8)
        val workers=A0PeerSearchWorkers({ action ->
            started.countDown()
            gate.await(2,TimeUnit.SECONDS)
            action()
        })
        try {
            // Up to 36 server offers may arrive almost simultaneously.
            // All must be admitted without expanding the peer socket budget.
            repeat(8) { assertTrue(workers.submit {}) }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            repeat(28) { assertTrue(workers.submit {}) }
            repeat(4) { assertTrue(workers.submit {}) }
            assertFalse(workers.submit {})
        } finally {
            gate.countDown()
            assertTrue(workers.stop(2000))
        }
        assertFalse(workers.submit {})
    }

    @Test(timeout=6000)
    fun widerPeerDiscoveryStillHasStrictParallelAndQueueLimits() {
        val release=CountDownLatch(1)
        val started=CountDownLatch(12)
        val concurrent=AtomicInteger()
        val peak=AtomicInteger()
        val workers=A0PeerSearchWorkers(
            action={ task ->
                val active=concurrent.incrementAndGet()
                peak.updateAndGet { maxOf(it,active) }
                started.countDown()
                release.await(2,TimeUnit.SECONDS)
                concurrent.decrementAndGet()
                task()
            },
            queueCapacity=128,parallelism=12,
        )
        try {
            repeat(12){assertTrue(workers.submit{})}
            assertTrue(started.await(2,TimeUnit.SECONDS))
            repeat(128){assertTrue(workers.submit{})}
            assertFalse(workers.submit{})
            assertEquals(12,peak.get())
        } finally {
            release.countDown()
            assertTrue(workers.stop(3000))
        }
    }

}
