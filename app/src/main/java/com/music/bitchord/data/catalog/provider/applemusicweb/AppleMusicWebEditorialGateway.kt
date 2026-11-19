package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderQueryExecutor
import java.util.Locale

/**
 * Public Apple Music Web editorial read boundary. Its data cannot be silently
 * substituted for the independent iTunes commercial catalog or a MusicBrainz
 * identity, and cannot be converted into a playable MediaAsset.
 *
 * Canonical callers receive an exact source/capability/storefront provenance
 * plus an explicit completeness outcome. No UI or Room models are constructed.
 */
internal data class AppleMusicWebEditorialResult<T>(
    val provider: ProviderKey,
    val capability: ProviderCapability,
    val storefront: String,
    val outcome: AppleMusicWebOutcome<T>,
)

/**
 * Two admission boundaries are intentional:
 * - the ProviderQueryExecutor owns the *whole* read, including transformations,
 *   ensuring mid-flight revocation cannot publish stale results;
 * - the AppleMusicWebPolicyTransport owns each physical HTTP dispatch.
 *
 * The production inventory still marks this provider BLOCKED; only explicitly
 * authorized test gates can exercise it before integration/readiness review.
 */
internal class AppleMusicWebEditorialGateway(
    private val catalog: AppleMusicWebCatalog,
    gate: ProviderPolicyGate,
) {
    private val executor = ProviderQueryExecutor(gate)
    private val key = BuiltInProviderKeys.APPLE_MUSIC_WEB

    private fun market(raw: String): String =
        raw.lowercase(Locale.ROOT).also {
            require(Regex("[a-z]{2}").matches(it)) { "Invalid Apple storefront" }
        }

    private fun validateItems(
        actual: List<AppleMusicWebItem>,
        storefront: String,
        allowedKinds: Set<AppleMusicWebKind>,
    ) {
        val seen = HashSet<String>()
        actual.forEach { item ->
            if (item.storefront != storefront || item.kind !in allowedKinds ||
                item.id.isBlank() || !seen.add(item.scopedId)
            ) throw AppleMusicWebProtocolException("Wrong, duplicated or foreign Apple resource")
        }
    }

    private suspend fun <T> guarded(
        capability: ProviderCapability,
        country: String,
        operation: suspend (storefront: String) -> AppleMusicWebOutcome<T>,
    ): ProviderCallResult<AppleMusicWebEditorialResult<T>> =
        executor.execute(capability, key) {
            val storefront = market(country)
            AppleMusicWebEditorialResult(
                provider = key,
                capability = capability,
                storefront = storefront,
                outcome = operation(storefront),
            )
        }

    /**
     * A complete *page* is not a claim that the whole search collection is
     * complete. Page.hasMore remains authoritative for pagination.
     */
    suspend fun search(
        kind: AppleMusicWebKind,
        query: String,
        country: String,
        page: Int = 1,
        limit: Int = 25,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebPage>> =
        guarded(ProviderCapability.CATALOG_SEARCH, country) { storefront ->
            val result = catalog.search(kind, query, storefront, page, limit)
            validateItems(result.items, storefront, setOf(kind))
            AppleMusicWebOutcome.Complete(result)
        }

    suspend fun artist(
        id: String,
        country: String,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebArtist>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val result = catalog.artist(id, storefront)
            if (result.identity.kind != AppleMusicWebKind.ARTIST ||
                result.identity.id != id || result.identity.storefront != storefront
            ) throw AppleMusicWebProtocolException("Wrong Apple artist identity")
            validateItems(result.topSongs, storefront, setOf(AppleMusicWebKind.SONG))
            validateItems(result.albums, storefront, setOf(AppleMusicWebKind.ALBUM))
            validateItems(result.singlesAndEps, storefront, setOf(AppleMusicWebKind.ALBUM))
            validateItems(result.featuredAlbums, storefront, setOf(AppleMusicWebKind.ALBUM))
            validateItems(result.similarArtists, storefront, setOf(AppleMusicWebKind.ARTIST))
            validateItems(result.artistPlaylists, storefront, setOf(AppleMusicWebKind.PLAYLIST))
            AppleMusicWebOutcome.Complete(result)
        }

    suspend fun album(
        id: String,
        country: String,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebAlbum>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val outcome = catalog.albumOutcome(id, storefront)
            fun validate(record: AppleMusicWebAlbum) {
                if (record.identity.kind != AppleMusicWebKind.ALBUM ||
                    record.identity.id != id || record.identity.storefront != storefront
                ) throw AppleMusicWebProtocolException("Wrong Apple album identity")
                validateItems(record.tracks, storefront, setOf(
                    AppleMusicWebKind.SONG, AppleMusicWebKind.MUSIC_VIDEO,
                ))
                validateItems(record.artists, storefront, setOf(AppleMusicWebKind.ARTIST))
            }
            when (outcome) {
                is AppleMusicWebOutcome.Complete -> validate(outcome.value)
                is AppleMusicWebOutcome.Partial -> validate(outcome.value)
                is AppleMusicWebOutcome.Unavailable -> Unit
            }
            outcome
        }

    suspend fun artistCollection(
        id: String,
        country: String,
        shelf: AppleMusicWebArtistShelf,
        page: Int = 1,
        limit: Int = 20,
    ): ProviderCallResult<AppleMusicWebEditorialResult<AppleMusicWebPage>> =
        guarded(ProviderCapability.EDITORIAL_DISCOGRAPHY, country) { storefront ->
            val result = catalog.artistCollection(id, storefront, shelf, page, limit)
            val kind = when (shelf) {
                AppleMusicWebArtistShelf.TOP_SONGS -> setOf(AppleMusicWebKind.SONG)
                AppleMusicWebArtistShelf.SIMILAR_ARTISTS -> setOf(AppleMusicWebKind.ARTIST)
                AppleMusicWebArtistShelf.MUSIC_VIDEOS -> setOf(AppleMusicWebKind.MUSIC_VIDEO)
                AppleMusicWebArtistShelf.ARTIST_PLAYLISTS,
                AppleMusicWebArtistShelf.FEATURED_PLAYLISTS -> setOf(AppleMusicWebKind.PLAYLIST)
                else -> setOf(AppleMusicWebKind.ALBUM)
            }
            validateItems(result.items, storefront, kind)
            AppleMusicWebOutcome.Complete(result)
        }
}
