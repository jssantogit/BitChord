package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.CatalogNativeIdentity
import com.music.bitchord.data.providers.CatalogSearchAdapter
import com.music.bitchord.data.providers.CatalogSearchItem
import com.music.bitchord.data.providers.CatalogSearchKind
import com.music.bitchord.data.providers.CatalogSearchPage
import com.music.bitchord.data.providers.CatalogSearchRequest
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability

/**
 * The Apple Web client is an adapter of the #219 catalog policy, never a
 * competing primary catalog or a conversion to iTunes/MusicBrainz identity.
 *
 * ProviderQueryExecutor in the gateway handles admission and cancellation.
 * Provider ordering and fallback remain in #219's shared catalog router.
 */
internal class AppleMusicWebSearchAdapter(
    private val gateway: AppleMusicWebEditorialGateway,
) : CatalogSearchAdapter {
    override val key = BuiltInProviderKeys.APPLE_MUSIC_WEB
    override val kinds: Set<CatalogSearchKind> = CatalogSearchKind.entries.toSet()

    override suspend fun search(
        request: CatalogSearchRequest,
    ): ProviderCallResult<CatalogSearchPage> {
        val sourceKind = when (request.kind) {
            CatalogSearchKind.ARTIST -> AppleMusicWebKind.ARTIST
            CatalogSearchKind.ALBUM -> AppleMusicWebKind.ALBUM
            CatalogSearchKind.TRACK -> AppleMusicWebKind.SONG
        }
        return when (val response = gateway.search(
            sourceKind, request.query, request.storefront, request.page, request.limit,
        )) {
            ProviderCallResult.Denied -> ProviderCallResult.Denied
            ProviderCallResult.Stale -> ProviderCallResult.Stale
            is ProviderCallResult.Failed -> ProviderCallResult.Failed(response.cause)
            is ProviderCallResult.Success -> {
                val source = response.value
                if (source.provider != key ||
                    source.capability != ProviderCapability.CATALOG_SEARCH
                ) {
                    ProviderCallResult.Failed(
                        AppleMusicWebProtocolException("Wrong Apple catalog provenance"),
                    )
                } else {
                    when (val outcome = source.outcome) {
                        is AppleMusicWebOutcome.Complete ->
                            ProviderCallResult.Success(project(request.kind, source.storefront,
                                outcome.value, partial = false))
                        is AppleMusicWebOutcome.Partial ->
                            ProviderCallResult.Success(project(request.kind, source.storefront,
                                outcome.value, partial = true))
                        is AppleMusicWebOutcome.Unavailable ->
                            ProviderCallResult.Failed(
                                AppleMusicWebProtocolException("Apple catalog unavailable"),
                            )
                    }
                }
            }
        }
    }

    private fun project(
        kind: CatalogSearchKind,
        storefront: String,
        source: AppleMusicWebPage,
        partial: Boolean,
    ): CatalogSearchPage = CatalogSearchPage(
        provider = key,
        capability = ProviderCapability.CATALOG_SEARCH,
        storefront = storefront,
        kind = kind,
        items = source.items.map { item ->
            val sourceKind = when (item.kind) {
                AppleMusicWebKind.ARTIST -> CatalogSearchKind.ARTIST
                AppleMusicWebKind.ALBUM -> CatalogSearchKind.ALBUM
                AppleMusicWebKind.SONG -> CatalogSearchKind.TRACK
                else -> throw AppleMusicWebProtocolException("Wrong Apple catalog type")
            }
            if (sourceKind != kind || item.storefront != storefront)
                throw AppleMusicWebProtocolException("Cross-source Apple catalog item")
            CatalogSearchItem(
                identity = CatalogNativeIdentity(
                    provider = key, kind = kind,
                    nativeId = item.id, storefront = item.storefront,
                ),
                title = item.title,
                subtitle = item.artistName,
                artworkUrl = item.artworkUrl,
                storeUrl = item.storeUrl,
            )
        },
        page = source.page,
        hasMore = source.hasMore,
        partial = partial,
    )
}
