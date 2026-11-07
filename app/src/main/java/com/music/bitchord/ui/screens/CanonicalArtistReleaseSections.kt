package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit

/** Catalog ReleaseGroup evidence, not a guess from titles or artist names. */
internal data class CanonicalArtistReleaseEvidence(
    val releaseId: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
)

/**
 * MusicBrainz edition chronology is direct, validated ReleaseId evidence.
 * Prefer the linked ReleaseGroup first date; use its own trusted Official
 * edition date only when no group date exists. Never derive a date from a
 * provider's upload/scan timestamp or a release title.
 */
internal fun canonicalArtistReleaseEvidenceWithTrustedDate(
    releaseId: String,
    groupEvidence: CanonicalArtistReleaseEvidence?,
    exactEditionDate: String?,
): CanonicalArtistReleaseEvidence =
    CanonicalArtistReleaseEvidence(
        releaseId = releaseId,
        primaryType = groupEvidence?.primaryType,
        firstReleaseDate = groupEvidence?.firstReleaseDate ?: exactEditionDate,
    )

/**
 * Presentation-only external date corroboration for an undated local artist.
 * The source artist must be unique by exact name; its MusicBrainz release
 * group results must come from a fielded Artist MBID search. An exact local
 * album title alone cannot create a canonical identity or persisted edge.
 *
 * Ignore ambiguous names, multi-edition collisions and non-editorial types.
 * These dates are used only to decorate the current Library artist page.
 */
internal fun canonicalLocalArtistExactReleaseDateEvidence(
    artistName: String,
    localReleases: List<ReleaseEntity>,
    matchingArtists: List<MusicBrainzArtistHit>,
    scopedReleaseGroups: List<MusicBrainzReleaseGroupHit>,
): Map<String, CanonicalArtistReleaseEvidence> {
    fun key(value: String) = value.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("""\s+"""), " ")
    val name = key(artistName)
    if (name.length < 2 || localReleases.isEmpty() ||
        matchingArtists.count { key(it.name) == name } != 1
    ) return emptyMap()
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    val groupCandidates = scopedReleaseGroups.filter { group ->
        val typed = group.primaryType?.lowercase(java.util.Locale.ROOT)
        val banned = group.secondaryTypes.orEmpty().lowercase(java.util.Locale.ROOT)
            .split('|')
        typed in setOf("album", "single", "ep") &&
            banned.none { it.trim() in setOf(
                "remix", "compilation", "live", "demo", "bootleg", "mixtape/street",
            ) } &&
            group.artistDisplay?.let(::key) == name &&
            group.firstReleaseDate?.let { raw ->
                runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
                    ?.let { !it.isAfter(today) }
            } == true
    }.groupBy { key(it.title) }
    return localReleases.mapNotNull { release ->
        // One exact group per title; same-name competing groups must stay
        // unknown rather than selecting an arbitrary release date.
        val matches = groupCandidates[key(release.title)].orEmpty()
        val matched = matches.singleOrNull() ?: return@mapNotNull null
        release.id to CanonicalArtistReleaseEvidence(
            release.id, matched.primaryType, matched.firstReleaseDate,
        )
    }.toMap()
}

internal data class CanonicalArtistReleaseSections(
    val albums: List<ReleaseEntity>,
    val singlesAndEps: List<ReleaseEntity>,
    val unclassified: List<ReleaseEntity>,
    val recentAlbum: ReleaseEntity?,
    val recentRelease: ReleaseEntity? = null,
)

/**
 * Preferred classification comes from a linked, verified ReleaseGroup type.
 *
 * When a user's local/remote album tag created a canonical Release without
 * the MusicBrainz edition edge, present a *browse-only* fallback based on
 * CURRENTLY available distinct release recordings: multiple => Albums,
 * exactly one => Singles e EPs. This is a UI grouping, not evidence that a
 * partially downloaded album is officially a single; never persist it as
 * ReleaseGroup truth or use it for metadata filters.
 *
 * Unknown/explicitly other types stay in "Lançamentos". A typed Album,
 * Single or EP with a verified full release date may become the hero release;
 * browse-only classifications cannot claim a new release.
 */
internal fun canonicalArtistReleaseSections(
    releases: List<ReleaseEntity>,
    evidence: Map<String, CanonicalArtistReleaseEvidence>,
    recordingCountsByRelease: Map<String, Int> = emptyMap(),
): CanonicalArtistReleaseSections {
    fun browseKind(release: ReleaseEntity): String {
        val type = evidence[release.id]?.primaryType?.trim()?.lowercase(java.util.Locale.ROOT)
        return when {
            type == "album" -> "album"
            type == "single" || type == "ep" -> "single"
            type.isNullOrEmpty() -> when (recordingCountsByRelease[release.id]) {
                1 -> "single"
                null, 0 -> "other"
                else -> "album"
            }
            else -> "other"
        }
    }
    val categorized = releases.groupBy(::browseKind)
    val validDate = Regex("\\d{4}-\\d{2}-\\d{2}")
    fun verifiedDay(release: ReleaseEntity): java.time.LocalDate? =
        evidence[release.id]?.firstReleaseDate
            ?.takeIf(validDate::matches)
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    // Both Library shelves order by real MusicBrainz release dates, newest
    // first. Undated local-only releases stay visible at the end instead of
    // being silently ordered by scanner/MediaStore insertion time.
    val latestFirst = compareByDescending<ReleaseEntity>(::verifiedDay)
        .thenBy { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
    val albums = categorized["album"].orEmpty().sortedWith(latestFirst)
    val singles = categorized["single"].orEmpty().sortedWith(latestFirst)
    val other = categorized["other"].orEmpty().sortedWith(latestFirst)
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    val recent = albums.firstOrNull { album ->
        evidence[album.id]?.primaryType?.trim()?.equals("Album", ignoreCase = true) == true &&
            verifiedDay(album)?.let { !it.isAfter(today) } == true
    }
    // The latest genuinely dated release may be a Single or EP, not only an
    // Album. Do not infer dates or types from a local track count.
    val latest = releases.mapNotNull { release ->
        val proof = evidence[release.id] ?: return@mapNotNull null
        // A proven edition date is enough for a generic "recent release"
        // even when its ReleaseGroup type has not been indexed yet. Do not
        // invent "Album", "Single" or "EP" from a track count.
        val type = proof.primaryType?.trim()?.lowercase(java.util.Locale.ROOT)
        if (type != null && type.isNotBlank() &&
            type !in setOf("album", "single", "ep")) return@mapNotNull null
        val date = proof.firstReleaseDate?.takeIf { validDate.matches(it) }
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { !it.isAfter(today) } ?: return@mapNotNull null
        date to release
    }.maxWithOrNull(compareBy<Pair<java.time.LocalDate, ReleaseEntity>> { it.first }
        .thenBy { it.second.id })?.second
    return CanonicalArtistReleaseSections(albums, singles, other, recent, latest)
}

/** Album detail includes all currently eligible recordings in this release,
 * not just recordings credited to the artist that led to this page. */
internal fun canonicalArtistReleaseTrackIds(
    availableRecordingIds: List<String>,
    releaseRecordingIds: Set<String>,
): List<String> = availableRecordingIds.filter { it in releaseRecordingIds }.distinct()

/** A recording or album cover is not evidence of an artist portrait. */
internal fun canonicalArtistHeroPhotoUrl(
    artistPhotoUrl: String?,
    recordingArtworkUrl: String?,
): String? = artistPhotoUrl?.takeIf { it.isNotBlank() }

/** Keep the original DetailScreen contract: search opens from the toolbar. */
internal fun shouldShowNativeArtistSearch(searching: Boolean, query: String): Boolean =
    searching || query.isNotBlank()

/** Artist photo is optional; leave no 60%-height empty canvas when it is missing. */
internal fun shouldCompactNativeArtistHero(artistPhotoUrl: String?): Boolean =
    artistPhotoUrl.isNullOrBlank()


/** Canonical release chronology is a display date, never MediaStore DATE_ADDED. */
internal fun canonicalArtistReleaseDateLabel(value: String?): String? {
    val date = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (Regex("""\d{4}""").matches(date)) return date
    val parsed = date.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    return parsed.format(java.time.format.DateTimeFormatter.ofPattern(
        "d 'de' MMM 'de' yyyy", java.util.Locale.forLanguageTag("pt-BR"),
    ))
}
