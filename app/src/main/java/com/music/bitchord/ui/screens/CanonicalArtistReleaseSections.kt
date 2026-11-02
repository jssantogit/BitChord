package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity

/** Catalog ReleaseGroup evidence, not a guess from titles or artist names. */
internal data class CanonicalArtistReleaseEvidence(
    val releaseId: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
)

internal data class CanonicalArtistReleaseSections(
    val albums: List<ReleaseEntity>,
    val singlesAndEps: List<ReleaseEntity>,
    val unclassified: List<ReleaseEntity>,
    val recentAlbum: ReleaseEntity?,
)

/**
 * A Release without trusted ReleaseGroup type stays visible in "Lançamentos";
 * it must not be mislabeled Album, Single or EP. "Álbum recente" is shown only
 * for an album carrying an actual complete release date.
 */
internal fun canonicalArtistReleaseSections(
    releases: List<ReleaseEntity>,
    evidence: Map<String, CanonicalArtistReleaseEvidence>,
): CanonicalArtistReleaseSections {
    val albums = releases.filter { evidence[it.id]?.primaryType?.equals("Album", true) == true }
    val singles = releases.filter {
        evidence[it.id]?.primaryType?.let { type ->
            type.equals("Single", true) || type.equals("EP", true)
        } == true
    }
    val classified = (albums.map { it.id } + singles.map { it.id }).toSet()
    val other = releases.filterNot { it.id in classified }
    val validDate = Regex("\\d{4}-\\d{2}-\\d{2}")
    val recent = albums.mapNotNull { album ->
        evidence[album.id]?.firstReleaseDate?.takeIf { validDate.matches(it) }?.let { day ->
            day to album
        }
    }.maxWithOrNull(compareBy<Pair<String, ReleaseEntity>> { it.first }
        .thenBy { it.second.id })?.second
    return CanonicalArtistReleaseSections(albums, singles, other, recent)
}
