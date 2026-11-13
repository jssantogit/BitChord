package com.music.bitchord.data.catalog.metadata

import java.util.Locale

/**
 * Progressive artist discography continuation over MusicBrainz's **raw**
 * server offsets. Incomplete, filtered or unstable browsing is never a
 * reason to remove existing artist -> ReleaseGroup associations.
 */
internal object MusicBrainzArtistDiscographyPager {
    internal data class Outcome(
        val items: List<MusicBrainzReleaseGroupHit>,
        val nextOffset: Int?,
        val complete: Boolean,
        /** Stronger than `complete`: every raw row was a unique, valid group. */
        val reconciliationEligible: Boolean = false,
        val sourceTotal: Int = 0,
    )

    private val mbid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun verifiedRawPage(
        page: MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>,
        expectedOffset: Int,
        expectedTotal: Int,
        seen: MutableSet<String>,
    ): Boolean {
        if (expectedTotal <= 0 || expectedOffset !in 0 until expectedTotal ||
            page.offset != expectedOffset || page.total != expectedTotal ||
            page.consumedCount <= 0 || page.consumedCount > expectedTotal - expectedOffset ||
            page.items.size != page.consumedCount
        ) return false
        // The graph decoder drops malformed rows and deduplicates by MBID.
        // Either event creates a raw/decoded mismatch, so it can NEVER
        // authorize removing an older official association.
        val ids = page.items.map { it.musicBrainzId.trim().lowercase(Locale.ROOT) }
        if (page.items.any { it.title.isBlank() } ||
            ids.any { !mbid.matches(it) } ||
            ids.distinct().size != ids.size ||
            ids.any { it in seen }
        ) return false
        seen.addAll(ids)
        return true
    }

    /** Single-page, source-complete proof, including the offset-zero boundary. */
    fun singlePageOutcome(
        first: MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>,
    ): Outcome {
        val decoded = first.items.distinctBy { it.musicBrainzId }
        val next = first.nextOffset
        val eligible = next == null &&
            verifiedRawPage(first, 0, first.total, mutableSetOf()) &&
            decoded.size == first.total
        return Outcome(decoded, next, next == null, eligible, first.total)
    }

    suspend fun collect(
        first: MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>,
        maxAdditionalPages: Int = 4,
        fetch: suspend (offset: Int) -> MusicBrainzCatalogGraphResult<
            MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>>,
        onPage: suspend (rows: List<MusicBrainzReleaseGroupHit>, nextOffset: Int?) -> Unit,
    ): Outcome {
        val found = LinkedHashMap<String, MusicBrainzReleaseGroupHit>()
        // A later duplicate must not overwrite the first title/type/date.
        first.items.forEach { found.putIfAbsent(it.musicBrainzId, it) }
        val total = first.total
        val seen = mutableSetOf<String>()
        var sourceVerified = verifiedRawPage(first, 0, total, seen)
        fun outcome(cursor: Int?, complete: Boolean) = Outcome(
            found.values.toList(), cursor, complete,
            reconciliationEligible = complete && cursor == null &&
                sourceVerified && found.size == total,
            sourceTotal = total,
        )
        if (first.consumedCount <= 0 && first.offset < first.total)
            return outcome(first.offset, false)
        var next = first.nextOffset
        val consumed = mutableSetOf<Int>()
        repeat(maxAdditionalPages.coerceIn(0, 8)) {
            val offset = next ?: return outcome(null, true)
            if (!consumed.add(offset)) return outcome(offset, false)
            when (val answer = fetch(offset)) {
                is MusicBrainzCatalogGraphResult.Failure ->
                    return outcome(offset, false)
                is MusicBrainzCatalogGraphResult.Success -> {
                    val page = answer.value
                    // Never fabricate a server cursor or trust a changed total.
                    if (page.offset != offset || page.total != total ||
                        page.consumedCount <= 0
                    ) return outcome(offset, false)
                    if (sourceVerified)
                        sourceVerified = verifiedRawPage(page, offset, total, seen)
                    page.items.forEach { found.putIfAbsent(it.musicBrainzId, it) }
                    next = page.nextOffset
                    onPage(found.values.toList(), next)
                }
            }
        }
        return outcome(next, next == null)
    }
}
