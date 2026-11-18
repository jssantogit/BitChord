package com.music.bitchord.data.providers

/**
 * Scrobbling submits personal listening history, so the existing account
 * consent AND the canonical (LISTENING_STATISTICS, provider instance) choice
 * must permit each dispatch. A cached credential is never sufficient.
 *
 * Rechecks the provider ticket after suspension and cancels active coroutine
 * work when the user disables exactly this provider.
 */
internal object ListeningSubmissionGate {
    fun permitted(
        gate: ProviderPolicyGate?,
        provider: ProviderKey,
    ): Boolean = gate?.open(ProviderCapability.LISTENING_STATISTICS, provider) != null

    fun permitted(
        provider: ProviderKey,
    ): Boolean = permitted(ProviderRuntime.gateOrNull(), provider)

    suspend fun <T> execute(
        gate: ProviderPolicyGate?,
        provider: ProviderKey,
        operation: suspend () -> T,
    ): ProviderCallResult<T> = if (gate == null) ProviderCallResult.Denied
        else ProviderQueryExecutor(gate).execute(
            ProviderCapability.LISTENING_STATISTICS, provider, operation,
        )

    suspend fun <T> execute(
        provider: ProviderKey,
        operation: suspend () -> T,
    ): ProviderCallResult<T> = execute(ProviderRuntime.gateOrNull(), provider, operation)
}
