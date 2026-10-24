package com.music.bitchord.ui.screens

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Real smart playlist planning runs on IO, but Media3 controller commands must
 * execute on its application looper, not on the thread that planned the batch.
 */
class SmartCollectionMedia3ThreadDispatchTest {

    @Test fun planningOnIoDispatchesOnlyControllerCommandToApplicationThread() = runBlocking {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "media3-controller-looper")
        }.asCoroutineDispatcher().use { controllerDispatcher ->
            val (before, command, after) = withContext(Dispatchers.IO) {
                val before = Thread.currentThread().name
                val command = onMediaControllerThread(controllerDispatcher) {
                    Thread.currentThread().name
                }
                Triple(before, command, Thread.currentThread().name)
            }
            assertEquals("media3-controller-looper", command)
            assertNotEquals("media3-controller-looper", before)
            assertNotEquals("media3-controller-looper", after)
        }
    }

    @Test fun controllerCommandExceptionPropagatesToExistingTokenCleanup() = runBlocking {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "media3-controller-looper")
        }.asCoroutineDispatcher().use { controllerDispatcher ->
            val failure = runCatching {
                withContext(Dispatchers.IO) {
                    onMediaControllerThread(controllerDispatcher) {
                        throw IllegalStateException("rejected on controller thread")
                    }
                }
            }.exceptionOrNull()
            assertEquals(IllegalStateException::class.java, failure?.javaClass)
        }
    }
}
