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
        val started=CountDownLatch(3)
        val workers=A0PeerSearchWorkers({ action ->
            started.countDown()
            gate.await(2,TimeUnit.SECONDS)
            action()
        })
        try {
            repeat(3) { assertTrue(workers.submit {}) }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            repeat(6) { assertTrue(workers.submit {}) }
            assertFalse(workers.submit {})
        } finally {
            gate.countDown()
            assertTrue(workers.stop(2000))
        }
        assertFalse(workers.submit {})
    }
}
