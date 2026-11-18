package com.music.bitchord.data.providers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Headless visibility/reactivity boundary for source-scoped cached data.
 *
 * A future screen or domain observer may keep collecting this Flow: opt-out
 * emits the unavailable value, while enabling a source re-reads its cache.
 * mapLatest cancels slow in-flight reads on revocation and avoids publishing
 * obsolete results. Unrelated capability changes do not re-run SQL.
 *
 * The underlying reader must ALSO enforce ProviderCachedReadAccess so a
 * direct call cannot bypass policy or return results after revocation.
 */
internal class ProviderReactiveCache(
    private val snapshots: StateFlow<ProviderSessionSnapshot>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(
        capability: ProviderCapability,
        provider: ProviderKey,
        unavailable: T,
        load: suspend () -> T,
    ): Flow<T> = snapshots
        .map { snapshot ->
            provider in snapshot.eligible[capability].orEmpty()
        }
        .distinctUntilChanged()
        .mapLatest { eligible -> if (eligible) load() else unavailable }
}
