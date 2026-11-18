package com.music.bitchord.data.providers

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult

/**
 * First existing catalog entrypoint available through Provider Engine 2.0.
 *
 * The default lookup delegates to the real MusicBrainz Android catalog client,
 * keeping its validation, MusicBrainz rate limiting and typed, non-playable
 * results. A fake lookup can be injected for isolated tests.
 *
 * This adapter is not yet wired to the UI: the existing legacy catalog read
 * path must remain unchanged until settings are migrated and the new routing
 * is connected end-to-end. No implicit fallback is attempted here.
 */
internal class MusicBrainzCapabilitySearch(
    private val executor: ProviderQueryExecutor,
    private val catalog: MusicBrainzCatalogSearch = MusicBrainzCatalogSearch(),
    private val lookupArtists: suspend (String, Int, Int) ->
        MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> =
        catalog::searchArtists,
) {
    /**
     * Guard any MusicBrainz catalog query at one common boundary, including
     * recordings, groups, editions and artist-scoped pagination.
     *
     * The block receives the existing typed MusicBrainz client. It is not
     * invoked at all for a disabled capability; late responses are rejected.
     * This is deliberately source-scoped, with no Apple or YouTube fallback.
     */
    suspend fun <T> executeCatalog(
        query: suspend MusicBrainzCatalogSearch.() -> MusicBrainzCatalogSearchResult<T>,
    ): ProviderCallResult<MusicBrainzCatalogSearchResult<T>> =
        executor.execute(ProviderCapability.CATALOG_SEARCH, PROVIDER_KEY) {
            catalog.query()
        }

    suspend fun searchArtists(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): ProviderCallResult<MusicBrainzCatalogSearchResult<MusicBrainzArtistHit>> =
        executor.execute(ProviderCapability.CATALOG_SEARCH, PROVIDER_KEY) {
            lookupArtists(query, limit, offset)
        }

    companion object {
        val PROVIDER_KEY = ProviderKey("musicbrainz")
    }
}
