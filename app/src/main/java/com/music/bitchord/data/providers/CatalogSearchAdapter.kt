package com.music.bitchord.data.providers

/**
 * Source-neutral public catalog contract.
 *
 * The #219 gate owns provider eligibility and ordering; adapters only provide
 * native IDs and editorial data. This layer does not create canonical identity,
 * media assets, fallback decisions, or a competing search state machine.
 */
internal enum class CatalogSearchKind { ARTIST, ALBUM, TRACK }

internal data class CatalogSearchRequest(
    val kind: CatalogSearchKind,
    val query: String,
    val storefront: String = "br",
    val page: Int = 1,
    val limit: Int = 25,
)

/** Native identity is scoped to its OWN source; storefronts are not MBIDs. */
internal data class CatalogNativeIdentity(
    val provider: ProviderKey,
    val kind: CatalogSearchKind,
    val nativeId: String,
    val storefront: String?,
) {
    init {
        require(nativeId.isNotBlank()) { "Catalog source identity is required" }
        require(storefront == null || storefront.isNotBlank())
    }
}

/** Metadata only. No videoId, stream URL or implicit playable state. */
internal data class CatalogSearchItem(
    val identity: CatalogNativeIdentity,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val storeUrl: String? = null,
)

/** Complete here means this requested PAGE; hasMore tracks remaining pages. */
internal data class CatalogSearchPage(
    val provider: ProviderKey,
    val capability: ProviderCapability,
    val storefront: String?,
    val kind: CatalogSearchKind,
    val items: List<CatalogSearchItem>,
    val page: Int,
    val hasMore: Boolean,
    val partial: Boolean,
)

internal interface CatalogSearchAdapter {
    val key: ProviderKey
    val kinds: Set<CatalogSearchKind>
    suspend fun search(request: CatalogSearchRequest): ProviderCallResult<CatalogSearchPage>
}

/**
 * Thin binding from registered adapters into #219's already existing ordering.
 * No Apple-first or MusicBrainz-first rule is allowed at this layer.
 * The caller owns active-filter coverage and fallback via CatalogSearchFilterRoute.
 */
internal fun orderedCatalogSearchAdapters(
    gate: ProviderPolicyGate?,
    kind: CatalogSearchKind,
    adapters: Collection<CatalogSearchAdapter>,
): List<CatalogSearchAdapter> {
    val byKey = adapters.associateBy(CatalogSearchAdapter::key)
    require(byKey.size == adapters.size) { "Duplicate catalog adapter for provider" }
    val supported = byKey.values.filter { kind in it.kinds }.map { it.key }.toSet()
    return orderedCatalogProviders(gate, ProviderCapability.CATALOG_SEARCH, supported)
        .mapNotNull(byKey::get)
}
