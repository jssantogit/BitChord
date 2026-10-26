package com.music.bitchord.data.catalog.metadata

/**
 * Progressive artist discography continuation over MusicBrainz's **raw**
 * server offsets. An incomplete/failed browse is never an instruction to
 * remove cached release groups. Each successfully fetched page is published
 * to the caller immediately; no GUI waits for full completion.
 */
internal object MusicBrainzArtistDiscographyPager {
    internal data class Outcome(
        val items: List<MusicBrainzReleaseGroupHit>,
        val nextOffset: Int?,
        val complete: Boolean,
    )

    suspend fun collect(
        first: MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>,
        maxAdditionalPages: Int = 4,
        fetch: suspend (offset: Int) -> MusicBrainzCatalogGraphResult<
            MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>>,
        onPage: suspend (rows: List<MusicBrainzReleaseGroupHit>, nextOffset: Int?) -> Unit,
    ): Outcome {
        val found = LinkedHashMap<String, MusicBrainzReleaseGroupHit>()
        first.items.forEach { found[it.musicBrainzId] = it }
        var next = first.nextOffset
        val total = first.total
        val consumed = mutableSetOf<Int>()
        repeat(maxAdditionalPages.coerceIn(0, 8)) {
            val offset = next ?: return Outcome(found.values.toList(), null, true)
            if (!consumed.add(offset)) return Outcome(found.values.toList(), offset, false)
            when (val answer = fetch(offset)) {
                is MusicBrainzCatalogGraphResult.Failure ->
                    return Outcome(found.values.toList(), offset, false)
                is MusicBrainzCatalogGraphResult.Success -> {
                    val page = answer.value
                    // Backend pagination may change while browsing. Reject a
                    // response for a different cursor, never invent one.
                    if (page.offset != offset || page.total != total || page.consumedCount <= 0)
                        return Outcome(found.values.toList(), offset, false)
                    page.items.forEach { found[it.musicBrainzId] = it }
                    next = page.nextOffset
                    onPage(found.values.toList(), next)
                }
            }
        }
        return Outcome(found.values.toList(), next, next == null)
    }
}
