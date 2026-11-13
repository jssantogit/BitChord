package com.music.bitchord.data.catalog.metadata

import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * An independently bounded MusicBrainz edition search, used ONLY for the
 * Apple-to-MB identity crosswalk. Its result is not a product feed.
 *
 * In particular, no partial page-1 list may certify uniqueness. The upstream
 * count, every offset, the number of raw rows consumed, and global MBID
 * uniqueness must agree before *any* candidate reaches a Release graph.
 */
internal suspend fun collectCompleteMusicBrainzEditionCandidates(
    maxRelevant: Int,
    isRelevant: (MusicBrainzReleaseHit) -> Boolean,
    searchPage: suspend (limit: Int, offset: Int) ->
        MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit>,
    pageSize: Int = 12,
    maxTotal: Int = 48,
    maxRequests: Int = 4,
): List<MusicBrainzReleaseHit>? {
    if (maxRelevant !in 1..8 || pageSize !in 1..100 ||
        maxTotal !in 1..100 || maxRequests !in 1..4 ||
        pageSize.toLong() * maxRequests < maxTotal
    ) return null

    val selected = ArrayList<MusicBrainzReleaseHit>(maxRelevant)
    val allIds = HashSet<String>()
    var total: Int? = null
    var next = 0
    repeat(maxRequests) {
        val result = try {
            searchPage(pageSize, next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val page = (result as? MusicBrainzCatalogSearchResult.Success)?.page
            ?: return null
        val expectedTotal = total ?: page.total.also { total = it }
        if (page.total != expectedTotal || page.total !in 1..maxTotal ||
            page.offset != next || page.consumedCount <= 0 ||
            page.consumedCount > pageSize ||
            // A non-terminal short page cannot prove that no source rows were
            // skipped (the MB search API normally fills each requested page).
            page.consumedCount != minOf(pageSize, page.total - next) ||
            page.items.size != page.consumedCount
        ) return null

        for (hit in page.items) {
            if (!allIds.add(hit.musicBrainzId.lowercase(Locale.ROOT))) return null
            if (isRelevant(hit)) {
                selected.add(hit)
                // We cannot prove uniqueness if more actual relevant editions
                // exist than the graph budget, even after a complete search.
                if (selected.size > maxRelevant) return null
            }
        }
        if (next + page.consumedCount == expectedTotal) return selected

        val cursor = page.nextOffset ?: return null
        if (cursor != next + page.consumedCount || cursor <= next) return null
        next = cursor
    }
    // Source fragmented pages beyond the strict request budget. Fail closed,
    // never treat the returned subset as all possible editions.
    return null
}
