package com.music.bitchord.ui

import com.music.bitchord.data.providers.ProviderKey

/**
 * Pure capability-scoped search selection: completion is checked for the
 * ACTIVE filter, not for an unrelated artist/album/track tab.
 *
 * This planner neither inspects nor converts identities. The caller holds
 * each source's query/session provenance and request lifecycle.
 */
internal enum class CatalogFilterCoverage {
    NOT_QUERIED, LOADING, EMPTY, MATCHING,
}

internal data class CatalogSearchFilterRoute(
    val provider: ProviderKey?,
    val shouldFetch: Boolean,
)

/**
 * Ordered by the user's CATALOG_SEARCH preferences. A pending primary owns
 * the screen until it settles; fallback is legal only after a relevant miss.
 */
internal fun catalogSearchFilterRoute(
    ordered: List<ProviderKey>,
    coverage: Map<ProviderKey, CatalogFilterCoverage>,
): CatalogSearchFilterRoute {
    for (provider in ordered) {
        when (coverage[provider] ?: CatalogFilterCoverage.NOT_QUERIED) {
            CatalogFilterCoverage.MATCHING, CatalogFilterCoverage.LOADING ->
                return CatalogSearchFilterRoute(provider, shouldFetch = false)
            CatalogFilterCoverage.NOT_QUERIED ->
                return CatalogSearchFilterRoute(provider, shouldFetch = true)
            CatalogFilterCoverage.EMPTY -> Unit
        }
    }
    return CatalogSearchFilterRoute(null, shouldFetch = false)
}
