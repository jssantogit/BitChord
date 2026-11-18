package com.music.bitchord.data.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * A composite editorial read may depend on several sources. All required
 * capability-instance pairs must remain authorized; authorization of the
 * final publisher never implies authorization of its evidence sources.
 */
internal class ProviderReadLease private constructor(
    private val gate: ProviderPolicyGate,
    private val tickets: List<ProviderRequestTicket>,
) {
    fun isCurrent(): Boolean = tickets.all(gate::accept)

    fun cancelJobOnRevoke(job: Job): () -> Unit {
        val unregister = tickets.map { ticket ->
            gate.onRevoke(ticket) {
                job.cancel(CancellationException("Required provider evidence revoked"))
            }
        }
        return { unregister.forEach { it() } }
    }

    companion object {
        fun acquire(
            gate: ProviderPolicyGate?,
            requirements: List<Pair<ProviderCapability, ProviderKey>>,
        ): ProviderReadLease? {
            if (gate == null || requirements.isEmpty()) return null
            val tickets = requirements.distinct().map { (capability, key) ->
                gate.open(capability, key) ?: return null
            }
            return ProviderReadLease(gate, tickets).takeIf { it.isCurrent() }
        }
    }
}
