package com.music.bitchord.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackListeningCommandQueueTest {
    @Test
    fun submittedCallbackFactsStayOrderedAcrossSuspendingWork() = runTest {
        val queue = PlaybackListeningCommandQueue(backgroundScope) { throw it }
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        queue.submit {
            order += "end-start"
            gate.await()
            order += "end-finished"
        }
        queue.submit {
            order += "next-start"
        }

        runCurrent()
        assertEquals(listOf("end-start"), order)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("end-start", "end-finished", "next-start"), order)
    }

    @Test
    fun failedCommandDoesNotDropLaterPlaybackFacts() = runTest {
        val failures = mutableListOf<String>()
        val order = mutableListOf<String>()
        val queue = PlaybackListeningCommandQueue(backgroundScope) {
            failures += requireNotNull(it.message)
        }

        queue.submit { error("boom") }
        queue.submit { order += "still-runs" }

        runCurrent()

        assertEquals(listOf("boom"), failures)
        assertEquals(listOf("still-runs"), order)
    }
}
