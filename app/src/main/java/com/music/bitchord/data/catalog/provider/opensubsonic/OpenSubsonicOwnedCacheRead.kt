package com.music.bitchord.data.catalog.provider.opensubsonic

import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderRuntime

/**
 * Source-owned Room data has two independent authorizations:
 * 1. the current PERSONAL_LIBRARY ticket for this exact account instance,
 * 2. the account/realm identity which must still match the original server.
 *
 * The common provider executor actively cancels a suspended Room read on
 * revocation. No stored assets are deleted by an opt-out.
 */
internal suspend fun <T> openSubsonicOwnedCacheRead(
    accountId: String,
    unavailable: T,
    gateProvider: () -> ProviderPolicyGate? = ProviderRuntime::gateOrNull,
    realmIsCurrent: () -> Boolean,
    fetch: suspend () -> T,
): T {
    if (!realmIsCurrent()) return unavailable
    val gate = gateProvider() ?: return unavailable
    return when (val result = ProviderQueryExecutor(gate).execute(
        ProviderCapability.PERSONAL_LIBRARY,
        openSubsonicCapabilityKey(accountId),
    ) {
        if (!realmIsCurrent()) unavailable else {
            val rows = fetch()
            if (realmIsCurrent()) rows else unavailable
        }
    }) {
        is ProviderCallResult.Success ->
            if (realmIsCurrent()) result.value else unavailable
        is ProviderCallResult.Failed,
        ProviderCallResult.Denied,
        ProviderCallResult.Stale -> unavailable
    }
}
