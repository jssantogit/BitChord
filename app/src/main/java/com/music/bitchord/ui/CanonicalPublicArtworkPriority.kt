package com.music.bitchord.ui

import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/**
 * CAA enrichment should cover the cards people actually see on Home, rather
 * than only the first 18 MusicBrainz results. Separate daily picks and format
 * sections otherwise show a row of generic music glyphs.
 *
 * This is a request priority only, never a guessed or fabricated artwork URI.
 */
internal fun canonicalWorldwideArtworkPriority(
    rows: List<CanonicalCatalogSearchRow>,
    todayIsoDate: String,
    limit: Int = 32,
): List<CanonicalCatalogSearchRow> {
    val valid = rows.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP &&
        canonicalEditorialReleaseType(it) != null }
    val launches = valid.take(12)
    val excluded = launches.map { it.musicBrainzId }.toSet()
    val dailyPicks = valid.asSequence()
        .filterNot { it.musicBrainzId in excluded }
        .sortedWith(compareBy<CanonicalCatalogSearchRow> {
            (it.musicBrainzId + todayIsoDate).hashCode()
        }.thenBy { it.musicBrainzId })
        .take(12).toList()
    val albums = valid.filter { canonicalEditorialReleaseType(it) == "Album" }.take(8)
    val singles = valid.filter {
        canonicalEditorialReleaseType(it) in setOf("Single", "EP")
    }.take(8)
    return (launches + dailyPicks + albums + singles)
        .distinctBy { it.musicBrainzId }
        .take(limit.coerceIn(0, 48))
}

internal fun canonicalGenreArtworkPriority(
    genre: CanonicalExploreGenre,
    rows: List<CanonicalCatalogSearchRow>,
    todayIsoDate: String,
    limit: Int = 24,
): List<CanonicalCatalogSearchRow> {
    val indexed = rows.associateBy { canonicalNativeBrowseId(it) }
    return canonicalGenreEditorialShelves(genre, rows, todayIsoDate)
        .flatMap { it.items }
        .mapNotNull { indexed[it.browseId] }
        .distinctBy { it.musicBrainzId }
        .take(limit.coerceIn(0, 48))
}
