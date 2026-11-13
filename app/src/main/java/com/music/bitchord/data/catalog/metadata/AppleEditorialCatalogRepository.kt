package com.music.bitchord.data.catalog.metadata

/**
 * Android catalog reader. iTunes controls commercial product existence and
 * storefront identity; MBIDs/playback are deliberately absent here.
 * This layer distinguishes a returned (possibly partial) catalog from an
 * unavailable catalog, so callers never silently substitute MB discovery.
 */
internal enum class AppleEditorialReadState {
    COMPLETE,
    POSSIBLY_TRUNCATED,
    PARTIAL_TRACKLIST,
    NOT_IN_STOREFRONT,
}

/**
 * Diagnostic coverage from actual Apple search probes, not a claim to know
 * the number of products available in the store. Does not store query text.
 */
internal data class AppleEditorialProbeReport(
    val eligibleSeeds: Int,
    val probesMade: Int,
    val matchingProducts: Int,
    val withoutMatchingProducts: Int,
    val failedRequests: Int,
    val incompleteResponses: Int,
    val budgetLimited: Boolean,
)

internal sealed interface AppleEditorialLoad {
    data class Success(
        val releases: List<ItunesEditorialRelease>,
        val state: AppleEditorialReadState,
        val probeReport: AppleEditorialProbeReport? = null,
    ) : AppleEditorialLoad

    data class Failure(val reason: ItunesCatalogFailure) : AppleEditorialLoad
}

internal sealed interface AppleEditorialArtistLoad {
    data class Success(
        val artists: List<ItunesCatalogItem>,
        val possiblyTruncated: Boolean,
    ) : AppleEditorialArtistLoad

    data class Failure(val reason: ItunesCatalogFailure) : AppleEditorialArtistLoad
}

internal sealed interface AppleEditorialTrackLoad {
    data class Success(
        val tracks: List<ItunesCatalogItem>,
        val possiblyTruncated: Boolean,
    ) : AppleEditorialTrackLoad

    data class Failure(val reason: ItunesCatalogFailure) : AppleEditorialTrackLoad
}

internal class AppleEditorialCatalogRepository(
    private val itunes: ItunesCatalogClient = ItunesCatalogClient(),
) {
    /** Artist presence and attribution come from Apple, not name-matched MBIDs. */
    suspend fun searchArtists(
        query: String,
        storefront: String = "br",
        limit: Int = 50,
    ): AppleEditorialArtistLoad = when (val source = itunes.search(
        query, ItunesCatalogEntity.ARTIST, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure ->
            AppleEditorialArtistLoad.Failure(source.reason)
        is ItunesCatalogResult.Success ->
            AppleEditorialArtistLoad.Success(
                artists = source.items.filter {
                    it.type == ItunesCatalogItemType.ARTIST && it.storefront == storefront
                },
                possiblyTruncated = source.possiblyTruncated,
            )
    }

    /** Apple songs are editorial recordings, never Song/videoId/playable assets. */
    suspend fun searchTracks(
        query: String,
        storefront: String = "br",
        limit: Int = 100,
    ): AppleEditorialTrackLoad = when (val source = itunes.search(
        query, ItunesCatalogEntity.SONG, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure ->
            AppleEditorialTrackLoad.Failure(source.reason)
        is ItunesCatalogResult.Success ->
            AppleEditorialTrackLoad.Success(
                tracks = source.items.filter {
                    it.type == ItunesCatalogItemType.TRACK && it.storefront == storefront
                },
                possiblyTruncated = source.possiblyTruncated,
            )
    }

    suspend fun searchAlbums(
        query: String,
        storefront: String = "br",
        limit: Int = 50,
    ): AppleEditorialLoad = when (val source = itunes.search(
        query, ItunesCatalogEntity.ALBUM, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure -> AppleEditorialLoad.Failure(source.reason)
        is ItunesCatalogResult.Success -> AppleEditorialLoad.Success(
            releases = ItunesEditorialCatalog.assemble(storefront, source.items),
            state = when {
                source.items.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                source.possiblyTruncated -> AppleEditorialReadState.POSSIBLY_TRUNCATED
                else -> AppleEditorialReadState.COMPLETE
            },
        )
    }

    /**
     * Extra PUBLIC iTunes discovery pass. We never use a MusicBrainz or
     * Discogs title as a visible release; every result must carry the exact
     * selected Apple artist ID and storefront. Can still hit the 200 cap.
     */
    suspend fun searchArtistAlbumCandidates(
        query: String,
        artistId: Long,
        storefront: String,
        attribute: ItunesCatalogSearchAttribute,
    ): AppleEditorialLoad {
        if (artistId <= 0) return AppleEditorialLoad.Failure(
            ItunesCatalogFailure.INVALID_QUERY)
        return when (val source = itunes.search(
            query, ItunesCatalogEntity.ALBUM, storefront, 200, attribute,
        )) {
            is ItunesCatalogResult.Failure ->
                AppleEditorialLoad.Failure(source.reason)
            is ItunesCatalogResult.Success -> {
                val matched = ItunesEditorialCatalog.assemble(storefront, source.items)
                    .filter { it.artistId == artistId }
                AppleEditorialLoad.Success(
                    releases = matched,
                    state = when {
                        source.possiblyTruncated ->
                            AppleEditorialReadState.POSSIBLY_TRUNCATED
                        matched.isEmpty() ->
                            AppleEditorialReadState.NOT_IN_STOREFRONT
                        else -> AppleEditorialReadState.COMPLETE
                    },
                )
            }
        }
    }

    suspend fun artistDiscography(
        artistId: Long,
        storefront: String = "br",
        limit: Int = 200,
    ): AppleEditorialLoad = when (val source = itunes.lookupArtistAlbums(
        artistId = artistId, country = storefront, limit = limit,
    )) {
        is ItunesCatalogResult.Failure -> AppleEditorialLoad.Failure(source.reason)
        is ItunesCatalogResult.Success -> {
            val releases = ItunesEditorialCatalog.assemble(storefront, source.items)
                .filter { it.artistId == artistId }
            AppleEditorialLoad.Success(
                releases = releases,
                state = when {
                    releases.isEmpty() -> AppleEditorialReadState.NOT_IN_STOREFRONT
                    source.possiblyTruncated -> AppleEditorialReadState.POSSIBLY_TRUNCATED
                    else -> AppleEditorialReadState.COMPLETE
                },
            )
        }
    }

    suspend fun albumDetail(
        collectionId: Long,
        storefront: String = "br",
    ): AppleEditorialLoad = when (val source = itunes.lookupAlbum(
        collectionId, country = storefront,
    )) {
        is ItunesCatalogResult.Failure -> AppleEditorialLoad.Failure(source.reason)
        is ItunesCatalogResult.Success -> {
            val release = ItunesEditorialCatalog.assemble(storefront, source.items)
                .singleOrNull { it.appleCollectionId == collectionId }
            if (release == null) {
                AppleEditorialLoad.Success(emptyList(), AppleEditorialReadState.NOT_IN_STOREFRONT)
            } else {
                val complete = release.trackCount != null && release.trackCount > 0 &&
                    release.tracks.size == release.trackCount &&
                    release.tracks.all { track ->
                        track.type == ItunesCatalogItemType.TRACK &&
                            track.collectionId == collectionId &&
                            track.discNumber != null && track.discNumber > 0 &&
                            track.trackNumber != null && track.trackNumber > 0
                    } &&
                    release.tracks.map { it.discNumber to it.trackNumber }.distinct().size ==
                        release.tracks.size
                AppleEditorialLoad.Success(
                    listOf(release),
                    if (complete && !source.possiblyTruncated) AppleEditorialReadState.COMPLETE
                    else AppleEditorialReadState.PARTIAL_TRACKLIST,
                )
            }
        }
    }
}
