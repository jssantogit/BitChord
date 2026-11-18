package com.music.bitchord.data.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/**
 * App-process-owned, single-flight local indexing. A Compose screen may leave
 * while waiting, but that must never cancel a device-wide MediaStore scan or
 * its Room transaction. Explicit refresh alone invalidates/cancels a worker.
 *
 * A failed scan is not cached; the next call can retry. Generation prevents
 * an invalidated worker from marking a new revision complete.
 */
internal class LocalIndexSingleFlight(private val workerScope: CoroutineScope) {
    private val guard = Any()
    private var generation = 0L
    private var complete = false
    private var inFlight: Deferred<Unit>? = null

    fun isComplete(): Boolean = synchronized(guard) { complete }

    suspend fun await(work: suspend () -> Unit) {
        val job: Deferred<Unit>? = synchronized(guard) {
            if (complete) null
            else inFlight?.takeUnless { it.isCompleted } ?: run {
                val generationAtStart = generation
                workerScope.async {
                    work()
                    synchronized(guard) {
                        if (generation == generationAtStart) complete = true
                    }
                }.also { inFlight = it }
            }
        }
        // Cancelling the caller only cancels its await, never the shared job.
        job?.await()
    }

    fun invalidate() {
        synchronized(guard) {
            generation++
            complete = false
            inFlight?.cancel()
            inFlight = null
        }
    }
}
