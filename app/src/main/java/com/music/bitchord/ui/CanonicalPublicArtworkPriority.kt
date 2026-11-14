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


/**
 * iTunes is the desired catalog-cover authority. Unlike CAA refresh queues,
 * this list includes already-covered groups: an older CAA bitmap is not
 * evidence that an exact Apple product has been checked. Keep the first
 * visible editorial cards responsive and let later batches be paged.
 */
internal fun canonicalAppleGroupCardArtworkCandidates(
    rows: List<CanonicalCatalogSearchRow>,
    limit: Int,
): List<CanonicalCatalogSearchRow> = rows.asSequence()
    .filter {
        it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) != null
    }
    .distinctBy { it.musicBrainzId }
    .take(limit.coerceIn(0, 48))
    .toList()

/**
 * MusicBrainz is still the identity/edition graph, but CAA is retired as
 * artwork for PUBLIC catalog UI. A persisted pre-migration Front-v5 must not
 * sneak back into newly rendered artist/release cards or album headers.
 *
 * Preserve unrelated local/remote artwork; this helper is scoped strictly to
 * public catalog rows, never the user's library or server album covers.
 */
internal fun canonicalPublicWithoutLegacyArchiveArtwork(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = try {
        java.net.URI(url).host?.lowercase(java.util.Locale.ROOT)
    } catch (_: Exception) { null }
    return if (host == "coverartarchive.org" || host == "www.coverartarchive.org" ||
        host == "archive.org" || host?.endsWith(".archive.org") == true
    ) null else url
}

