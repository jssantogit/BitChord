package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult

/**
 * A complete, independently fetched set of MusicBrainz artist release groups.
 * This is deliberately NOT a guessed list of albums from the first 75 rows.
 */
internal data class DiscogsArtistGroupPage(
    val groups: List<Pair<String, String>>, // validated MBID -> title
    val total: Int,
    val offset: Int,
    val consumedCount: Int,
)

private val GROUP_MBID =
    Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

/**
 * Returns null for partial, inconsistent, oversized or stale pages: only a
 * complete lookup may justify excluding MusicBrainz-known titles from a
 * source-only Discogs artist shelf. A complete empty catalog returns emptySet.
 */
internal suspend fun collectCompleteDiscogsArtistGroupTitles(
    pageSize: Int = 75,
    maxGroups: Int = 300,
    fetchPage: suspend (offset: Int) -> DiscogsArtistGroupPage?,
): Set<String>? {
    require(pageSize in 1..100)
    require(maxGroups in 1..300)
    var offset = 0
    var total: Int? = null
    val identities = HashSet<String>()
    val titles = LinkedHashSet<String>()
    val maxPages = (maxGroups + pageSize - 1) / pageSize
    repeat(maxPages) {
        val page = fetchPage(offset) ?: return null
        if (page.offset != offset || page.total !in 0..maxGroups ||
            (total != null && total != page.total) ||
            page.consumedCount != page.groups.size ||
            page.consumedCount > pageSize
        ) return null
        total = page.total
        if (page.total == 0) {
            return if (offset == 0 && page.groups.isEmpty()) emptySet() else null
        }
        if (page.consumedCount <= 0 || page.consumedCount > page.total - offset) return null
        for ((id, name) in page.groups) {
            if (!GROUP_MBID.matches(id) || name.isBlank() ||
                !identities.add(id.lowercase(java.util.Locale.ROOT))
            ) return null
            titles += name.trim()
        }
        offset += page.consumedCount
        if (offset == page.total && identities.size == page.total) return titles
    }
    return null
}

/** At most four bounded requests, only after the user opens an artist page. */
internal suspend fun loadCompleteMusicBrainzArtistGroupTitles(
    artistMbid: String,
    graph: MusicBrainzCatalogGraph = MusicBrainzCatalogGraph(),
): Set<String>? = collectCompleteDiscogsArtistGroupTitles { offset ->
    when (val result = graph.browseReleaseGroupsForArtist(artistMbid, limit = 75, offset = offset)) {
        is MusicBrainzCatalogGraphResult.Success -> result.value.let { page ->
            DiscogsArtistGroupPage(
                groups = page.items.map { it.musicBrainzId to it.title },
                total = page.total,
                offset = page.offset,
                consumedCount = page.consumedCount,
            )
        }
        is MusicBrainzCatalogGraphResult.Failure -> null
    }
}
