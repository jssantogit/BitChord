package com.music.bitchord.ui.screens

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            // Coroutine debugging appends " @coroutine#N" to JVM thread names.
            // Check the executor identity prefix instead of the debug suffix.
            assertTrue(command.startsWith("media3-controller-looper"))
            assertFalse(before.startsWith("media3-controller-looper"))
            assertFalse(after.startsWith("media3-controller-looper"))
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
