package com.music.bitchord.data.catalog.metadata

/**
 * Custom progressive discovery for the PUBLIC iTunes Search API.
 * There is no remote offset/cursor. These are app-owned *display pages*
 * plus bounded, independently verified Apple searches for additional IDs.
 * MusicBrainz/Discogs titles can be passed as hints, never as visible items.
 */
internal interface AppleArtistAlbumDiscoverySource {
    suspend fun initial(id: Long, country: String): AppleEditorialLoad
    suspend fun byArtist(artist: String, id: Long, country: String): AppleEditorialLoad
    suspend fun byTitle(title: String, id: Long, country: String): AppleEditorialLoad
}

internal data class AppleArtistPageSnapshot(
    val visible: List<ItunesEditorialRelease>,
    val discoveredCount: Int,
    val hasMore: Boolean,
    /** No Apple endpoint provided a total count or proof of exhaustiveness. */
    val coverageUncertain: Boolean,
    val pendingHints: Int,
    val allowsTargetedSearch: Boolean = true,
)

internal sealed interface AppleArtistPageLoad {
    data class Success(val snapshot: AppleArtistPageSnapshot, val added: Int) : AppleArtistPageLoad
    data class Failure(val reason: ItunesCatalogFailure) : AppleArtistPageLoad
    data object InvalidQuery : AppleArtistPageLoad
}

/**
 * Stateful only within one artist page; caller serializes requests and cancels
 * the session when leaving the artist. Append order stays stable when loading
 * more, preventing top-of-list jumps on large discographies.
 */
internal class AppleArtistDiscographyPager(
    private val artistId: Long,
    private val artistName: String,
    private val country: String,
    private val pageSize: Int = 24,
    private val source: AppleArtistAlbumDiscoverySource,
    /** Candidate discovery is lazy: never delay initial artist rendering. */
    private val titleHintSource: AppleArtistTitleHintSource? = null,
    /** Only an independently verified crosswalk may fill this MB artist ID. */
    private val verifiedMbArtistId: String? = null,
) {
    init {
        require(artistId > 0 && Regex("[a-z]{2}").matches(country))
        require(artistName.isNotBlank() && pageSize in 1..100)
    }

    private val products = linkedMapOf<Long, ItunesEditorialRelease>()
    private val disputed = mutableSetOf<Long>()
    private val hints = ArrayDeque<String>()
    private val triedHints = mutableSetOf<String>()
    private var visibleCount = 0
    private var initialLoaded = false
    private var originalCapped = false
    private var broadSearched = false
    private var hintSearchCompleted = false
    private var unresolvedCoverage = false

    fun snapshot(): AppleArtistPageSnapshot = AppleArtistPageSnapshot(
        visible = products.values.take(visibleCount),
        discoveredCount = products.size,
        hasMore = products.size > visibleCount ||
            (initialLoaded && originalCapped &&
                (!broadSearched || !hintSearchCompleted)) ||
            hints.isNotEmpty(),
        coverageUncertain = unresolvedCoverage || disputed.isNotEmpty(),
        pendingHints = hints.size,
    )

    private fun accept(result: AppleEditorialLoad.Success): Int {
        if (result.state == AppleEditorialReadState.POSSIBLY_TRUNCATED ||
            result.state == AppleEditorialReadState.PARTIAL_TRACKLIST
        ) unresolvedCoverage = true
        var added = 0
        result.releases.forEach { release ->
            if (release.artistId != artistId || release.storefront != country ||
                release.appleCollectionId <= 0 ||
                release.appleScopedId != "itunes:$country:album:${release.appleCollectionId}" ||
                release.title.isBlank() || release.appleCollectionId in disputed
            ) return@forEach
            val previous = products[release.appleCollectionId]
            if (previous == null) {
                products[release.appleCollectionId] = release
                added++
            } else if (previous.title != release.title ||
                previous.artistId != release.artistId ||
                (previous.upc != null && release.upc != null && previous.upc != release.upc)
            ) {
                // Contradictory Apple editions are not arbitrarily merged.
                products.remove(release.appleCollectionId)
                disputed.add(release.appleCollectionId)
                unresolvedCoverage = true
                visibleCount = minOf(visibleCount, products.size)
            }
        }
        return added
    }

    suspend fun start(): AppleArtistPageLoad {
        if (initialLoaded) return AppleArtistPageLoad.Success(snapshot(), 0)
        val result = source.initial(artistId, country)
        if (result is AppleEditorialLoad.Failure)
            return AppleArtistPageLoad.Failure(result.reason)
        result as AppleEditorialLoad.Success
        val added = accept(result)
        initialLoaded = true
        originalCapped = result.state == AppleEditorialReadState.POSSIBLY_TRUNCATED
        visibleCount = minOf(pageSize, products.size)
        return AppleArtistPageLoad.Success(snapshot(), added)
    }

    /** Comparison only: Apple itself may append a display-level - EP / - Single. */
    private fun titleKey(text: String): String = java.text.Normalizer.normalize(
        text.trim(), java.text.Normalizer.Form.NFKC,
    ).replace(Regex("""\\s[-–—]\\s(?:EP|Single)$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\\s+"""), " ")
        .lowercase(java.util.Locale.ROOT)

    /** Sources of candidate *names*, not authoritative Apple products. */
    fun enqueueTitleHints(names: List<String>) {
        val alreadyShown = products.values.mapTo(mutableSetOf()) { titleKey(it.title) }
        names.take(256).forEach { raw ->
            val name = raw.trim()
            val key = titleKey(name)
            if (name.length in 2..120 && name.none(Char::isISOControl) &&
                key !in alreadyShown && key !in triedHints &&
                hints.none { titleKey(it) == key }
            ) hints.addLast(name)
        }
    }

    suspend fun loadMore(): AppleArtistPageLoad {
        if (!initialLoaded) return start()
        if (products.size > visibleCount) {
            visibleCount = minOf(products.size, visibleCount + pageSize)
            return AppleArtistPageLoad.Success(snapshot(), 0)
        }
        var added = 0
        if (originalCapped && !broadSearched) {
            when (val result = source.byArtist(artistName, artistId, country)) {
                is AppleEditorialLoad.Failure ->
                    return AppleArtistPageLoad.Failure(result.reason)
                is AppleEditorialLoad.Success -> {
                    broadSearched = true
                    added += accept(result)
                }
            }
        }
        if (added == 0 && originalCapped && broadSearched &&
            !hintSearchCompleted && hints.isEmpty()
        ) {
            // Query public metadata only when the Apple result actually hit
            // its cap. MB/Discogs provide names, not commercial products.
            // Failure is non-fatal: search-by-title remains usable.
            hintSearchCompleted = true
            val additional = try {
                titleHintSource?.suggest(artistName, verifiedMbArtistId).orEmpty()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                hintSearchCompleted = false
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            enqueueTitleHints(additional)
        }
        if (added == 0) {
            // Two bounded title probes max per turn; global iTunes limiter
            // enforces Apple's approx. 20 requests/minute budget.
            repeat(2) {
                if (hints.isEmpty() || added > 0) return@repeat
                val title = hints.first()
                when (val result = source.byTitle(title, artistId, country)) {
                    is AppleEditorialLoad.Failure ->
                        return AppleArtistPageLoad.Failure(result.reason)
                    is AppleEditorialLoad.Success -> {
                        hints.removeFirst()
                        triedHints.add(title.lowercase(java.util.Locale.ROOT))
                        added += accept(result)
                    }
                }
            }
        }
        visibleCount = minOf(products.size, visibleCount + pageSize)
        return AppleArtistPageLoad.Success(snapshot(), added)
    }

    /** Manual within-artist discovery remains possible after automatic probes end. */
    suspend fun searchMore(title: String): AppleArtistPageLoad {
        if (!initialLoaded) return AppleArtistPageLoad.InvalidQuery
        val query = title.trim()
        if (query.length !in 2..120 || query.any(Char::isISOControl))
            return AppleArtistPageLoad.InvalidQuery
        return when (val result = source.byTitle(query, artistId, country)) {
            is AppleEditorialLoad.Failure -> AppleArtistPageLoad.Failure(result.reason)
            is AppleEditorialLoad.Success -> {
                val added = accept(result)
                // Display the requested discoveries immediately, without
                // reordering any previously displayed Apple editions.
                visibleCount = products.size
                AppleArtistPageLoad.Success(snapshot(), added)
            }
        }
    }
}

internal class ItunesArtistAlbumDiscoverySource(
    private val catalog: AppleEditorialCatalogRepository,
) : AppleArtistAlbumDiscoverySource {
    override suspend fun initial(id: Long, country: String): AppleEditorialLoad =
        catalog.artistDiscography(id, country, limit = 200)

    override suspend fun byArtist(
        artist: String, id: Long, country: String,
    ): AppleEditorialLoad = catalog.searchArtistAlbumCandidates(
        query = artist, artistId = id, storefront = country,
        attribute = ItunesCatalogSearchAttribute.ARTIST_TERM,
    )

    override suspend fun byTitle(
        title: String, id: Long, country: String,
    ): AppleEditorialLoad = catalog.searchArtistAlbumCandidates(
        query = title, artistId = id, storefront = country,
        attribute = ItunesCatalogSearchAttribute.ALBUM_TERM,
    )
}
