package com.music.bitchord.data.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A screen owns only its waiting UI, not the device-wide MediaStore sync. */
class LocalIndexSingleFlightTest {
    @Test fun navigationCancellationDoesNotDiscardAnInFlightIndex() = runBlocking {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = LocalIndexSingleFlight(worker)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var runs = 0
            val firstScreen = launch {
                gate.await {
                    runs++
                    entered.complete(Unit)
                    finish.await()
                }
            }
            entered.await()
            firstScreen.cancelAndJoin()
            assertFalse(gate.isComplete())
            val reopenedScreen = async { gate.await { runs++ } }
            finish.complete(Unit)
            reopenedScreen.await()
            assertTrue(gate.isComplete())
            gate.await { runs++ }
            assertEquals(1, runs)
        } finally {
            worker.cancel()
        }
    }

    @Test fun explicitRefreshInvalidatesOldWorkerAndStartsExactlyOneNewScan() = runBlocking {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = LocalIndexSingleFlight(worker)
            val entered = CompletableDeferred<Unit>()
            val firstScreen = launch {
                try {
                    gate.await {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // invalidate() is the only action permitted to cancel it.
                }
            }
            entered.await()
            gate.invalidate()
            firstScreen.join()
            assertFalse(gate.isComplete())
            var runs = 0
            gate.await { runs++ }
            gate.await { runs++ }
            assertTrue(gate.isComplete())
            assertEquals(1, runs)
        } finally {
            worker.cancel()
        }
    }

    @Test fun failedScanIsNotCachedAsSuccess() = runBlocking {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = LocalIndexSingleFlight(worker)
            var attempts = 0
            try {
                gate.await {
                    attempts++
                    error("temporary MediaStore failure")
                }
                throw AssertionError("A failed index must propagate its failure")
            } catch (expected: IllegalStateException) {
                assertEquals("temporary MediaStore failure", expected.message)
            }
            assertFalse(gate.isComplete())
            gate.await { attempts++ }
            assertEquals(2, attempts)
            assertTrue(gate.isComplete())
        } finally {
            worker.cancel()
        }
    }
}
