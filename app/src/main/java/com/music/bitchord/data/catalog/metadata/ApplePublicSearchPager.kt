package com.music.bitchord.data.catalog.metadata

/**
 * PUBLIC iTunes Search API has a 200-item cap and no documented offset.
 * The app pages already-discovered Apple IDs locally and, *only when capped*,
 * probes different official search attributes. This is discovery, NOT a
 * server cursor or an assurance that the search results are exhaustive.
 */
internal interface ApplePublicSearchSource {
    suspend fun search(
        query: String,
        entity: ItunesCatalogEntity,
        country: String,
        attribute: ItunesCatalogSearchAttribute?,
    ): ItunesCatalogResult
}

internal class ItunesPublicSearchSource(
    private val client: ItunesCatalogClient = ItunesCatalogClient(),
) : ApplePublicSearchSource {
    override suspend fun search(
        query: String,
        entity: ItunesCatalogEntity,
        country: String,
        attribute: ItunesCatalogSearchAttribute?,
    ) = client.search(query, entity, country, limit = 200, attribute = attribute)
}

internal data class ApplePublicSearchSnapshot(
    val visible: List<ItunesCatalogItem>,
    val discoveredCount: Int,
    val hasMore: Boolean,
    val coverageUncertain: Boolean,
)

internal sealed interface ApplePublicSearchPageLoad {
    data class Success(val snapshot: ApplePublicSearchSnapshot) : ApplePublicSearchPageLoad
    data class Failure(val reason: ItunesCatalogFailure) : ApplePublicSearchPageLoad
}

internal class ApplePublicSearchPager(
    private val query: String,
    private val entity: ItunesCatalogEntity,
    private val country: String,
    private val pageSize: Int = 24,
    private val source: ApplePublicSearchSource = ItunesPublicSearchSource(),
) {
    init {
        require(Regex("[a-z]{2}").matches(country))
        require(pageSize in 1..100)
    }

    private val rows = linkedMapOf<Long, ItunesCatalogItem>()
    private val disputed = mutableSetOf<Long>()
    private val extraAttributes = when (entity) {
        ItunesCatalogEntity.ARTIST -> listOf(
            ItunesCatalogSearchAttribute.ARTIST_TERM,
        )
        ItunesCatalogEntity.ALBUM -> listOf(
            ItunesCatalogSearchAttribute.ALBUM_TERM,
            ItunesCatalogSearchAttribute.ARTIST_TERM,
        )
        ItunesCatalogEntity.SONG -> listOf(
            ItunesCatalogSearchAttribute.SONG_TERM,
            ItunesCatalogSearchAttribute.ARTIST_TERM,
        )
    }
    private val expected = when (entity) {
        ItunesCatalogEntity.ARTIST -> ItunesCatalogItemType.ARTIST
        ItunesCatalogEntity.ALBUM -> ItunesCatalogItemType.ALBUM
        ItunesCatalogEntity.SONG -> ItunesCatalogItemType.TRACK
    }
    private var initialized = false
    private var capped = false
    private var visibleCount = 0
    private var nextAttribute = 0

    fun snapshot() = ApplePublicSearchSnapshot(
        visible = rows.values.take(visibleCount),
        discoveredCount = rows.size,
        hasMore = rows.size > visibleCount || (capped && nextAttribute < extraAttributes.size),
        coverageUncertain = capped || disputed.isNotEmpty(),
    )

    private fun merge(result: ItunesCatalogResult.Success) {
        result.items.forEach { row ->
            if (row.type != expected || row.storefront != country ||
                row.id <= 0L || row.title.isBlank() || row.id in disputed
            ) return@forEach
            val prev = rows[row.id]
            if (prev == null) {
                rows[row.id] = row
            } else if (prev.title != row.title || prev.artistId != row.artistId ||
                prev.collectionId != row.collectionId
            ) {
                rows.remove(row.id)
                disputed.add(row.id)
                visibleCount = minOf(visibleCount, rows.size)
            }
        }
    }

    suspend fun start(): ApplePublicSearchPageLoad {
        if (initialized) return ApplePublicSearchPageLoad.Success(snapshot())
        if (query.trim().isEmpty() || query.length > 120 ||
            query.any(Char::isISOControl)
        ) return ApplePublicSearchPageLoad.Failure(ItunesCatalogFailure.INVALID_QUERY)
        return when (val result = source.search(query, entity, country, null)) {
            is ItunesCatalogResult.Failure ->
                ApplePublicSearchPageLoad.Failure(result.reason)
            is ItunesCatalogResult.Success -> {
                initialized = true
                capped = result.possiblyTruncated
                merge(result)
                visibleCount = minOf(pageSize, rows.size)
                ApplePublicSearchPageLoad.Success(snapshot())
            }
        }
    }

    suspend fun loadMore(): ApplePublicSearchPageLoad {
        if (!initialized) return start()
        if (rows.size > visibleCount) {
            visibleCount = minOf(rows.size, visibleCount + pageSize)
            return ApplePublicSearchPageLoad.Success(snapshot())
        }
        if (!capped || nextAttribute >= extraAttributes.size) {
            return ApplePublicSearchPageLoad.Success(snapshot())
        }
        val attribute = extraAttributes[nextAttribute]
        return when (val result = source.search(query, entity, country, attribute)) {
            is ItunesCatalogResult.Failure ->
                ApplePublicSearchPageLoad.Failure(result.reason)
            is ItunesCatalogResult.Success -> {
                nextAttribute++
                merge(result)
                visibleCount = minOf(rows.size, visibleCount + pageSize)
                ApplePublicSearchPageLoad.Success(snapshot())
            }
        }
    }
}
