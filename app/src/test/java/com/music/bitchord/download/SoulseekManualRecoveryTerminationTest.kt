package com.music.bitchord.download

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SoulseekManualRecoveryTerminationTest {
    @Test fun releaseCannotPrecedeWorkerTerminationEvenWhenShutdownInterrupts() {
        val worker=Executors.newSingleThreadExecutor()
        val entered=CountDownLatch(1)
        val allowExit=CountDownLatch(1)
        val released=CountDownLatch(1)
        worker.execute {
            entered.countDown()
            try {
                allowExit.await()
            } catch(_:InterruptedException) {
                // Model a blocking publish that is exiting safely but not yet done.
                allowExit.await()
            }
        }
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        SoulseekManualRecoveryTermination.releaseAfterStop(worker) {
            released.countDown()
        }
        assertFalse("lease released while A3 worker still owns publication",
            released.await(100,TimeUnit.MILLISECONDS))
        allowExit.countDown()
        assertTrue(released.await(3,TimeUnit.SECONDS))
        assertTrue(worker.isTerminated)
    }

    @Test fun idleExecutorReleasesWithoutAbandoningLease() {
        val worker=Executors.newSingleThreadExecutor()
        val released=CountDownLatch(1)
        SoulseekManualRecoveryTermination.releaseAfterStop(worker) {
            released.countDown()
        }
        assertTrue(released.await(3,TimeUnit.SECONDS))
        assertTrue(worker.isTerminated)
    }
}
