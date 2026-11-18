package com.music.bitchord.data.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/** A request was denied before dispatch, or invalidated before publication. */
sealed interface ProviderCallResult<out T> {
    data object Denied : ProviderCallResult<Nothing>
    data object Stale : ProviderCallResult<Nothing>
    data class Success<T>(val value: T) : ProviderCallResult<T>
    data class Failed(val cause: Exception) : ProviderCallResult<Nothing>
}

/**
 * One capability-safe dispatch boundary, independent from any provider SDK.
 *
 * The adapter lambda is not called unless the current gate permits it.
 * When the specific (capability, provider instance) is revoked mid-flight,
 * the active child operation is cancelled immediately via SupervisorJob and Stale is returned.
 * External caller cancellation is preserved and propagated.
 */
class ProviderQueryExecutor(private val gate: ProviderPolicyGate) {
    suspend fun <T> execute(
        capability: ProviderCapability,
        key: ProviderKey,
        operation: suspend () -> T,
    ): ProviderCallResult<T> {
        val ticket = gate.open(capability, key) ?: return ProviderCallResult.Denied
        return supervisorScope {
            var revoked = false
            val job = async { operation() }
            val unregister = gate.onRevoke(ticket) {
                revoked = true
                job.cancel(CancellationException("Provider capability revoked"))
            }
            try {
                val value = job.await()
                if (!revoked && gate.accept(ticket)) ProviderCallResult.Success(value)
                else ProviderCallResult.Stale
            } catch (cancelled: CancellationException) {
                if (revoked || !gate.accept(ticket)) ProviderCallResult.Stale
                else throw cancelled
            } catch (failure: Exception) {
                if (!revoked && gate.accept(ticket)) ProviderCallResult.Failed(failure)
                else ProviderCallResult.Stale
            } finally {
                unregister()
            }
        }
    }
}
