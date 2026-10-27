package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale

/**
 * A resolved release owns its track list. Every Recording in that list may
 * reuse the already verified release sleeve, without a second MusicBrainz/CAA
 * search per track. An explicit per-track image retains precedence.
 *
 * Only call with a cover vetted for this particular Release/ReleaseGroup;
 * never use this to fuzzy-join unrelated recordings from search.
 */
internal fun canonicalReleaseTracksWithArtwork(
    rows: List<CanonicalCatalogSearchRow>,
    releaseArtworkUri: String?,
): List<CanonicalCatalogSearchRow> {
    if (releaseArtworkUri.isNullOrBlank()) return rows
    return rows.map { row ->
        if (row.kind == CanonicalSearchKind.RECORDING &&
            row.artworkUri.isNullOrBlank()
        ) row.copy(artworkUri = releaseArtworkUri) else row
    }
}

/**
 * Release Groups are user-facing albums/EPs/singles; Releases are editions.
 * Pick a defensible default to show the native tracklist immediately, while
 * preserving the complete set of MusicBrainz IDs for explicit edition browsing.
 *
 * MusicBrainz status and an actual group-title match outrank geography/date.
 * This is selection of a display *edition*, NOT deduplication of audio identities.
 */
/**
 * Album cards only borrow covers from real editions in their exact MusicBrainz
 * ReleaseGroup, with a verified Official status and matching edition title.
 * The group's own CAA Front can originate from a video/alternate edition.
 */
private val alternateArtworkPackaging = Regex(
    """(?i)\b(?:ver\.?|version|vinyl|photobook|jewel\s*case|photocard|cover\s*ver\.?|limited|collector|reissue|remaster)\b"""
)

/**
 * Official status + group MBID proves group membership, not that an alternate
 * edition's packaging depicts the canonical sleeve. Permit exact or clearly
 * related work titles, including a composite Group's leading edition title.
 */
internal fun canonicalEditionTitleIsSuitableForGroupArtwork(
    editionTitle: String, groupTitle: String,
): Boolean {
    val edition = editionTitle.trim()
    val group = groupTitle.trim()
    if (edition.isEmpty() || group.isEmpty() ||
        alternateArtworkPackaging.containsMatchIn(edition)
    ) return false
    return edition.contains(group, ignoreCase = true) ||
        group.startsWith("${edition} & ", ignoreCase = true)
}

internal fun canonicalArtworkEditionsForGroup(
    editions: List<MusicBrainzReleaseHit>, groupTitle: String,
): List<MusicBrainzReleaseHit> =
    canonicalPreferredEditions(editions, groupTitle)
        .filter { edition ->
            // A ReleaseGroup's conceptual title is not necessarily the official
            // Release title: “How Sweet & Bubble Gum” owns official “How Sweet”
            // editions. MBID membership + Official status, NOT string equality,
            // is the provenance boundary. Curated title rejects video variants.
            isCuratedMusicBrainzRelease(edition) &&
                // MusicBrainz can tell us that an edition has no CAA Front.
                edition.hasFrontCover != false &&
                // Reject editions with unrelated work titles or alternate
                // collectible packaging even when their group MBID matches.
                canonicalEditionTitleIsSuitableForGroupArtwork(edition.title, groupTitle)
        }
        // Artwork choice is separate from tracklist edition preference:
        // positively known covers avoid slow dead-end CAA requests.
        .sortedWith(
            compareByDescending<MusicBrainzReleaseHit> {
                it.title.trim().equals(groupTitle.trim(), ignoreCase = true)
            }.thenByDescending { it.hasFrontCover == true },
        )
        .take(2)

/**
 * An edition's public title is distinct from its parent ReleaseGroup title.
 * On composite group labels, use a verified Official edition's shorter
 * leading title as the displayed label; do not mutate the canonical group ID.
 * Never infer a title from an artist, recording, track count or an unrelated
 * release; inconclusive cases retain the original MusicBrainz group label.
 */
internal fun canonicalOfficialGroupDisplayTitle(
    groupTitle: String,
    editions: List<MusicBrainzReleaseHit>,
): String {
    val prefix = groupTitle.trim()
    val matching = canonicalPreferredEditions(editions, groupTitle).filter { edition ->
        isCuratedMusicBrainzRelease(edition) &&
            edition.title.isNotBlank() &&
            // Only a clear composite-group -> edition relationship is
            // unambiguous enough for offline editorial display.
            prefix.startsWith(edition.title.trim() + " & ", ignoreCase = true)
    }
    return matching.firstOrNull()?.title?.trim() ?: groupTitle
}

internal fun canonicalPreferredEditions(
    editions: List<MusicBrainzReleaseHit>,
    groupTitle: String,
): List<MusicBrainzReleaseHit> {
    val normalizedGroup = groupTitle.trim().lowercase(Locale.ROOT)
    val modifier = Regex(
        """(?i)(?:\b(?:sped\s*up|slowed|nightcore|remix|bootleg|instrumental|deluxe|special\s+edition|repackage|karaoke|live)\b)"""
    )
    val unique = editions.distinctBy { it.musicBrainzId }
    // "Official" is positive release evidence. A Bootleg, Promotion, withdrawn
    // or pseudo-release must not become the user's default tracklist merely
    // because it is the earliest/only edition with a playable-looking title.
    // Unknown status does not prove an official audio release, even if this is
    // the only edition in the local cache. It remains stored for verification,
    // but must never become the default "official" streaming tracklist.
    val official = unique.filter { it.status.equals("Official", ignoreCase = true) }
    return official.sortedWith(
        compareBy<MusicBrainzReleaseHit> {
            when (it.status?.lowercase(Locale.ROOT)) {
                "official" -> 0
                null, "" -> 1
                "promotion" -> 2
                "pseudo-release" -> 3
                else -> 4
            }
        }.thenBy {
            if (it.title.trim().lowercase(Locale.ROOT) == normalizedGroup) 0 else 1
        }.thenBy {
            if (modifier.containsMatchIn(it.title)) 1 else 0
        }.thenBy {
            if (it.releaseDate?.matches(Regex("""\d{4}(-\d{2}){0,2}""")) == true) 0 else 1
        }.thenBy {
            // A precise date is better evidence than a year-only placeholder.
            when (it.releaseDate?.length) {
                10 -> 0
                7 -> 1
                4 -> 2
                else -> 3
            }
        }.thenBy {
            it.releaseDate ?: "9999-12-31"
        }.thenBy {
            when (it.country) {
                "XW" -> 0 // International release, if all stronger evidence ties.
                "KR" -> 1
                else -> 2
            }
        }.thenBy { it.musicBrainzId },
    )
}
