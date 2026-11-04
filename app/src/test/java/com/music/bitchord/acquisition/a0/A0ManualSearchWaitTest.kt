package com.music.bitchord.acquisition.a0

import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class A0ManualSearchWaitTest {
    @Test fun interruptedWaitIsCancellationAndPreservesInterruptFlag() {
        val inbox=LinkedBlockingQueue<String>()
        Thread.currentThread().interrupt()
        try {
            try {
                A0ManualSearchWait.poll(inbox,6,TimeUnit.SECONDS)
                fail("Interrupted search must not proceed")
            }catch(_:CancellationException) {
                assertTrue(Thread.currentThread().isInterrupted)
            }
        }finally { Thread.interrupted() }
    }

    @Test fun ordinaryTimeoutDoesNotTurnIntoCancellation() {
        val inbox=LinkedBlockingQueue<String>()
        assertNull(A0ManualSearchWait.poll(inbox,0,TimeUnit.MILLISECONDS))
        inbox.offer("ready")
        assertEquals("ready",A0ManualSearchWait.poll(inbox,0,TimeUnit.MILLISECONDS))
    }
}
