package com.music.bitchord.ui

import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.time.LocalDate

/**
 * Single native artist hero policy for an ONLINE MusicBrainz artist.
 *
 * A named release is not enough: choose the newest genuinely dated, typed
 * ReleaseGroup. MusicBrainz catalog metadata is not proof of playable audio.
 * Undated / future / non-release results remain available in ordinary shelves.
 */
internal fun canonicalPublicArtistFeaturedRelease(
    rows: List<CanonicalCatalogSearchRow>,
    todayIsoDate: String,
): CanonicalCatalogSearchRow? {
    val today = runCatching { LocalDate.parse(todayIsoDate) }.getOrNull()
        ?: return null
    val fullDate = Regex("""\d{4}-\d{2}-\d{2}""")
    return rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) in setOf("Album", "Single", "EP") }
        .mapNotNull { row ->
            val day = row.releaseDate?.takeIf(fullDate::matches)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeUnless { it.isAfter(today) } ?: return@mapNotNull null
            day to row
        }
        .maxWithOrNull(compareBy<Pair<LocalDate, CanonicalCatalogSearchRow>> { it.first }
            .thenBy { it.second.musicBrainzId })
        ?.second
}
