package com.music.bitchord.data.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coalesces *concurrent, identical* provider operations. Not a result cache:
 * completed entries disappear as soon as the last caller has consumed them.
 *
 * Only the first observer launches work. Later observers share that exact
 * in-flight response. Cancelling one observer preserves work needed by others;
 * cancelling the final observer cancels the underlying operation.
 *
 * Each coordinator has ONE result type at compile time: a text HTTP response
 * cannot be accidentally conflated with Unit, a parsed DTO or another type.
 * Keys MUST include provider instance, capability, authorization generation
 * and the complete normalized request identity. Never put credentials in a key.
 * Failure is not remembered and unrelated operations never share a slot.
 *
 * This lightweight coordinator owns no background polling or recurring work.
 */
internal class ProviderSingleFlight<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Entry<T>(
        val deferred: Deferred<T>,
        var subscribers: Int,
    )

    private val mutex = Mutex()
    private val ongoing = mutableMapOf<Any, Entry<T>>()

    suspend fun run(
        key: Any,
        operation: suspend () -> T,
    ): T {
        val entry = mutex.withLock {
            val existing = ongoing[key]
            if (existing != null) {
                existing.subscribers++
                existing
            } else {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    operation()
                }
                Entry(deferred, subscribers = 1).also { ongoing[key] = it }
            }
        }

        try {
            entry.deferred.start()
            return entry.deferred.await()
        } finally {
            // A cancelled observer still has to remove its subscription.
            // Never run this cleanup in the cancelled caller's context.
            withContext(NonCancellable) {
                mutex.withLock {
                    entry.subscribers--
                    if (entry.subscribers == 0) {
                        if (ongoing[key] === entry) ongoing.remove(key)
                        if (!entry.deferred.isCompleted) {
                            entry.deferred.cancel()
                        }
                    }
                }
            }
        }
    }

    /**
     * Policy-bound single-flight with active cancellation: denies immediately
     * if [gate] does not permit (capability, provider) and cancels the underlying
     * in-flight deferred task as soon as the policy revokes that specific
     * (capability, provider instance) pair.
     */
    suspend fun runGuarded(
        gate: ProviderPolicyGate?,
        capability: ProviderCapability,
        provider: ProviderKey,
        key: Any,
        operation: suspend () -> T,
    ): T {
        val ticket = gate?.open(capability, provider)
        if (gate != null && ticket == null) {
            throw ProviderRequestDeniedException(capability, provider)
        }
        val entry = mutex.withLock {
            val existing = ongoing[key]
            if (existing != null) {
                existing.subscribers++
                existing
            } else {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    operation()
                }
                Entry(deferred, subscribers = 1).also { ongoing[key] = it }
            }
        }

        val unregister = if (gate != null && ticket != null) {
            gate.onRevoke(ticket) {
                if (!entry.deferred.isCompleted) {
                    entry.deferred.cancel(CancellationException("Provider capability revoked"))
                }
            }
        } else {
            {}
        }

        try {
            entry.deferred.start()
            val value = entry.deferred.await()
            if (gate != null && ticket != null && !gate.accept(ticket)) {
                throw ProviderRequestDeniedException(capability, provider)
            }
            return value
        } catch (cancelled: CancellationException) {
            if (gate != null && ticket != null && !gate.accept(ticket)) {
                throw ProviderRequestDeniedException(capability, provider)
            }
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                unregister()
                mutex.withLock {
                    entry.subscribers--
                    if (entry.subscribers == 0) {
                        if (ongoing[key] === entry) ongoing.remove(key)
                        if (!entry.deferred.isCompleted) {
                            entry.deferred.cancel()
                        }
                    }
                }
            }
        }
    }

    /** Diagnostics/test visibility only; no result data is exposed. */
    suspend fun activeCount(): Int = mutex.withLock { ongoing.size }

    /** Diagnostics/test visibility only; no request bodies are exposed. */
    suspend fun activeWaiters(key: Any): Int =
        mutex.withLock { ongoing[key]?.subscribers ?: 0 }
}
