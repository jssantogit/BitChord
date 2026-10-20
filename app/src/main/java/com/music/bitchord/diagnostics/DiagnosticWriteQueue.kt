package com.music.bitchord.diagnostics

import java.util.TreeSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-writer bounded persistence queue for diagnostics.
 *
 * Producers never wait for queue capacity: a saturated data queue rejects the
 * write and counts it as dropped. One executor slot is reserved for serialized
 * control operations so maintenance such as Clear cannot itself be rejected.
 * Flushes do not enqueue a barrier task; instead they wait for the contiguous
 * completion watermark covering every write attempt issued before the flush.
 */
internal class DiagnosticWriteQueue(
    queueCapacity: Int,
    threadFactory: ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "diagnostics-writer").apply { isDaemon = true }
    },
) {
    init {
        require(queueCapacity > 0) { "queueCapacity must be greater than zero" }
    }

    private val completion = CompletionTracker()
    private val dropped = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val dataQueueSlots = Semaphore(queueCapacity)
    private val submissionLock = ReentrantLock()
    private val controlLock = ReentrantLock()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity + 1),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )

    val droppedWrites: Long
        get() = dropped.get()

    /** Actual writer block exceptions observed during this process. */
    val failedWrites: Long
        get() = failed.get()

    /**
     * Attempts to enqueue [block] without waiting for data queue capacity.
     *
     * Normal writes may use only the configured data slots. The extra executor
     * slot is reserved for a control operation. A data slot is released when
     * its write starts running, matching the executor's queued-capacity model.
     */
    fun offer(block: () -> Unit): Boolean = submissionLock.withLock {
        val sequence = completion.issue()
        if (!dataQueueSlots.tryAcquire()) {
            dropped.incrementAndGet()
            completion.complete(sequence)
            return@withLock false
        }

        try {
            executor.execute {
                dataQueueSlots.release()
                try {
                    block()
                } catch (error: Throwable) {
                    failed.incrementAndGet()
                    throw error
                } finally {
                    completion.complete(sequence)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            dataQueueSlots.release()
            dropped.incrementAndGet()
            completion.complete(sequence)
            false
        }
    }

    /**
     * Runs [block] on the single writer after every operation submitted before
     * this call. Only one control operation can be in flight, and its reserved
     * queue slot means saturation by normal diagnostic writes cannot reject it.
     * Normal producers remain non-blocking with respect to queue capacity.
     */
    fun <T> runControl(block: () -> T): T = controlLock.withLock {
        val task = FutureTask(Callable { block() })
        submissionLock.withLock {
            executor.execute(task)
        }
        try {
            task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    /**
     * Waits only for write attempts issued before this call.
     * Later diagnostics cannot extend the wait indefinitely.
     */
    fun awaitWritesIssuedBeforeCall(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0L) { "timeoutMillis must not be negative" }
        val target = completion.issuedThrough()
        return try {
            completion.awaitThrough(target, timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private class CompletionTracker {
        private val issued = AtomicLong(0L)
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val completedOutOfOrder = TreeSet<Long>()
        private var completedThrough = 0L

        fun issue(): Long = issued.incrementAndGet()

        fun issuedThrough(): Long = issued.get()

        fun complete(sequence: Long) {
            lock.withLock {
                if (sequence <= completedThrough) return
                if (sequence == completedThrough + 1L) {
                    completedThrough = sequence
                    while (completedOutOfOrder.remove(completedThrough + 1L)) {
                        completedThrough += 1L
                    }
                } else {
                    completedOutOfOrder.add(sequence)
                }
                changed.signalAll()
            }
        }

        @Throws(InterruptedException::class)
        fun awaitThrough(target: Long, timeoutMillis: Long): Boolean = lock.withLock {
            if (completedThrough >= target) return true
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (completedThrough < target && remainingNanos > 0L) {
                remainingNanos = changed.awaitNanos(remainingNanos)
            }
            completedThrough >= target
        }
    }
}
