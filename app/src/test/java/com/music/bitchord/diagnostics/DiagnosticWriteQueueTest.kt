package com.music.bitchord.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticWriteQueueTest {

    @Test
    fun `flush does not skip earlier accepted writes when a later write is rejected`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val queue = DiagnosticWriteQueue(queueCapacity = 1)

        assertTrue(
            queue.offer {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            },
        )
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        assertTrue(queue.offer { secondFinished.countDown() })
        assertFalse(queue.offer { error("rejected write must never run") })
        assertEquals(1L, queue.droppedWrites)

        assertFalse(queue.awaitWritesIssuedBeforeCall(timeoutMillis = 25L))

        releaseFirst.countDown()

        assertTrue(queue.awaitWritesIssuedBeforeCall(timeoutMillis = 2_000L))
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
    }
}
