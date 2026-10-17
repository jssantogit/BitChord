package com.music.bitchord.diagnostics

import java.util.TreeSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-writer bounded persistence queue for diagnostics.
 *
 * Producers never wait for queue capacity: a saturated queue rejects the write
 * and counts it as dropped. Flushes do not enqueue a barrier task (which could
 * itself be rejected); instead they wait for the contiguous completion
 * watermark covering every write attempt issued before the flush began.
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
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )

    val droppedWrites: Long
        get() = dropped.get()

    /**
     * Attempts to enqueue [block] without waiting for capacity.
     *
     * A sequence is issued before submission so a concurrent flush cannot skip
     * an attempt that has started submission but has not yet been accepted or
     * rejected by the executor.
     */
    fun offer(block: () -> Unit): Boolean {
        val sequence = completion.issue()
        return try {
            executor.execute {
                try {
                    block()
                } finally {
                    completion.complete(sequence)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            dropped.incrementAndGet()
            completion.complete(sequence)
            false
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
