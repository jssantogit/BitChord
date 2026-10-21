package com.music.bitchord.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** A late Catalog callback must never resurrect stream truth after Media3 close. */
class CanonicalRemoteTruthGateTest {
    @Test fun closeClearsPriorObservationsAndRejectsLateCallbacks() {
        val gate = CanonicalRemoteTruthGate<String>()
        gate.publish("http_headers")
        assertEquals("http_headers", gate.latest())
        gate.close()
        assertNull(gate.latest())
        gate.publish("late_http_bytes")
        assertNull(gate.latest())
        gate.close()
        assertNull(gate.latest())
    }

    @Test fun simultaneousCloseAndCallbacksCannotRestoreClosedState() {
        val gate = CanonicalRemoteTruthGate<String>()
        gate.publish("initial")
        val ready = CountDownLatch(1)
        val start = CountDownLatch(1)
        val worker = Thread {
            ready.countDown()
            start.await()
            repeat(256) { gate.publish("delayed") }
        }
        worker.start()
        assertTrue(ready.await(3, TimeUnit.SECONDS))
        start.countDown()
        gate.close()
        worker.join(3_000)
        assertFalse(worker.isAlive)
        assertNull(gate.latest())
        gate.publish("after_join")
        assertNull(gate.latest())
    }
}
