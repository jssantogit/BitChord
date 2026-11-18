package com.music.bitchord.data.providers

/**
 * Restricts source-owned cache reads independently of database persistence.
 *
 * Fail-closed when no gate is supplied in production Android runtime:
 * absence of authorization never grants permission. Only pure non-Android
 * unit test environments may fall back to a permissive test gate.
 *
 * A disabled source is not queried, and a source disabled while a Room query
 * is suspended cannot publish stale rows.
 */
internal class ProviderCachedReadAccess(
    private val policyGate: () -> ProviderPolicyGate? = {
        ProviderRuntime.gateForCacheReadOrNull()
    },
) {
    internal fun currentGateOrNull(): ProviderPolicyGate? = policyGate()

    suspend fun <T> read(
        capability: ProviderCapability,
        provider: ProviderKey,
        unavailable: T,
        fetch: suspend () -> T,
    ): T {
        val gate = policyGate() ?: return unavailable
        // The common provider executor revokes the active child operation
        // immediately. A post-read ticket check alone leaves a blocked Room
        // query or rate-limit wait alive after the user disables its source.
        return when (val result = ProviderQueryExecutor(gate).execute(
            capability, provider, fetch,
        )) {
            is ProviderCallResult.Success -> result.value
            is ProviderCallResult.Failed -> throw result.cause
            ProviderCallResult.Denied, ProviderCallResult.Stale -> unavailable
        }
    }

    companion object {
        /** Explicit test helper for non-Android unit tests without ProviderRuntime. */
        fun permissiveForUnitTests(
            provider: ProviderKey = ProviderKey("musicbrainz"),
            capabilities: Set<ProviderCapability> = ProviderCapability.entries.toSet(),
        ): ProviderCachedReadAccess {
            val testGate = ProviderPolicyGate(
                ProviderCapabilityPolicy(
                    descriptors = listOf(
                        ProviderDescriptor(provider, capabilities, ProviderReadiness.READY),
                    ),
                    choices = capabilities.associateWith {
                        listOf(ProviderChoice(provider, enabled = true, order = 0))
                    },
                ),
            )
            return ProviderCachedReadAccess { testGate }
        }
    }
}
