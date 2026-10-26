package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Opaque navigation-only keys must never contain user files, provider IDs or URLs. */
private fun opaqueHomeCardId(namespace: String, assetKey: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(assetKey.toByteArray(StandardCharsets.UTF_8))
    return namespace + ":" + bytes.joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

/** A card is keyed by the exact local asset, never by a recycled legacy video handle. */
internal fun canonicalLocalPickCardId(song: Song): String {
    require(isPlayableLocalPick(song)) { "Home picks require a verified local URI" }
    return opaqueHomeCardId("local:pick", song.localUri!!)
}


/** Canonical navigation targets the EXISTING native artist/release DetailScreen. */
internal fun canonicalNativePageType(kind: CanonicalSearchKind): BrowseType? = when (kind) {
    CanonicalSearchKind.ARTIST -> BrowseType.ARTIST
    CanonicalSearchKind.RELEASE_GROUP, CanonicalSearchKind.RELEASE -> BrowseType.ALBUM
    CanonicalSearchKind.RECORDING -> null
}

internal fun canonicalNativeBrowseId(row: CanonicalCatalogSearchRow): String =
    "catalog:${row.kind}:${row.musicBrainzId}"

/** Native DetailScreen accepts real browse shelves without synthetic Song.videoId. */
internal fun canonicalNativeSections(
    parentKind: CanonicalSearchKind,
    rows: List<CanonicalCatalogSearchRow>,
    heading: String,
): List<HomeShelf> {
    val kind = when (parentKind) {
        CanonicalSearchKind.ARTIST -> CanonicalSearchKind.RELEASE_GROUP
        CanonicalSearchKind.RELEASE_GROUP -> CanonicalSearchKind.RELEASE
        else -> return emptyList()
    }
    val cards = rows.asSequence().filter { it.kind == kind }
        .distinctBy { it.kind to it.musicBrainzId }
        .map { row ->
            ShelfItem(
                title = row.title,
                subtitle = row.releaseKind ?: row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = canonicalNativeBrowseId(row),
            )
        }.toList()
    return if (cards.isNotEmpty()) listOf(HomeShelf(heading, cards)) else emptyList()
}

/** A server asset is a verified catalog selection, never a synthetic video or stream URL. */
internal fun canonicalRemotePickCardId(row: CanonicalServerSearchRow): String {
    require(isTrustedHomeServerAsset(row)) { "Home Server picks require a trusted MediaAsset" }
    return opaqueHomeCardId("server:pick", row.providerKey + "|" + row.mediaAssetId)
}

internal fun isTrustedHomeServerAsset(row: CanonicalServerSearchRow): Boolean =
    (row.providerKey.startsWith("opensubsonic:") || row.providerKey.startsWith("jellyfin:")) &&
        row.mediaAssetId.isNotBlank() && row.recordingId.isNotBlank()

internal fun isPlayableLocalPick(song: Song): Boolean =
    song.localUri?.let { it.startsWith("content://") || it.startsWith("file://") } == true


/**
 * A decorative artist thumbnail can be requested only for the first exact
 * artist-name match. Any alternative MBIDs remain distinct and get no
 * inferred image. Apple portrait is NOT canonical identity evidence.
 */
internal fun canonicalArtistArtworkCandidate(
    rows: List<CanonicalCatalogSearchRow>,
    query: String,
): CanonicalCatalogSearchRow? {
    val text = query.trim()
    if (text.isBlank()) return null
    return rows.firstOrNull {
        it.kind == CanonicalSearchKind.ARTIST &&
            it.title.equals(text, ignoreCase = true) &&
            it.artworkUri.isNullOrBlank()
    }
}

/**
 * CoverArtArchive has art for releases and release-groups, NOT artists or
 * recordings. Never guess an edition/group by title to fill a thumbnail.
 */
internal fun canonicalCoverLookupTarget(
    row: CanonicalCatalogSearchRow,
): Pair<String?, String?>? = when (row.kind) {
    CanonicalSearchKind.RELEASE_GROUP -> null to row.musicBrainzId
    CanonicalSearchKind.RELEASE -> row.musicBrainzId to row.coverGroupMbid
    CanonicalSearchKind.RECORDING -> row.coverReleaseMbid?.let { it to null }
    CanonicalSearchKind.ARTIST -> null
}

/**
 * Old builds persisted 250px CAA URLs. They are safe identity claims, but
 * are not acceptable quality for a hero or a full-width Home release card.
 * Never upgrade a generic image URL by guessing a different provider's asset.
 */
internal fun canonicalNeedsCoverRefresh(artwork: String?): Boolean =
    artwork.isNullOrBlank() ||
        ((artwork.contains("coverartarchive.org/") || artwork.contains("archive.org/")) &&
            artwork.endsWith("-250.jpg"))

/** Bound network enrichment to a small visible subset, with stable identities. */
internal fun canonicalCoverLookupCandidates(
    rows: List<CanonicalCatalogSearchRow>,
    limit: Int,
): List<CanonicalCatalogSearchRow> = rows.asSequence()
    .filter { canonicalNeedsCoverRefresh(it.artworkUri) &&
        (canonicalCoverLookupTarget(it) != null || it.kind == CanonicalSearchKind.RECORDING) }
    .distinctBy { it.kind to it.musicBrainzId }
    .take(limit.coerceAtLeast(0))
    .toList()

/**
 * Producer-neutral Home content for the existing Android HomeScreen, not a parallel screen.
 *
 * MusicBrainz proves releases and names but NOT listening popularity or audio.
 * Picks require an existing local URI. Trending requires recorded plays supplied
 * by the Listening Event Store AND a linked catalog MediaAsset, revalidated at play.
 * A shelf without honest evidence is omitted instead of publishing fake charts.
 */
internal fun canonicalCuratedHomeShelves(
    savedReleases: List<ShelfItem>,
    metadata: List<CanonicalCatalogSearchRow>,
    localPicks: List<Song>,
    trendingRecordingMbids: List<String>,
    trendingLocalPicks: List<Song> = emptyList(),
    releasesHeading: String,
    picksHeading: String,
    trendingHeading: String,
    todayIsoDate: String,
    remotePicks: List<CanonicalServerSearchRow> = emptyList(),
): List<HomeShelf> = buildList {
    val datePattern = Regex("^\\d{4}(-\\d{2}(-\\d{2})?)?$")
    val releasedCandidates = metadata.asSequence()
        .filter {
            it.kind == CanonicalSearchKind.RELEASE_GROUP ||
                it.kind == CanonicalSearchKind.RELEASE
        }
        .filter { row ->
            val kind = row.releaseKind?.lowercase()
            kind == null || kind == "album" || kind == "single" || kind == "ep"
        }
        .filter { row ->
            val date = row.releaseDate ?: return@filter true
            val normalized = when {
                date.length == 4 -> "$date-01-01"
                date.length == 7 -> "$date-01"
                else -> date
            }
            date.matches(datePattern) && normalized <= todayIsoDate
        }
        .toList()
    // An ineligible/future group must never suppress an already-published
    // edition. Prefer eligible groups as work identities, then real editions.
    val groups = releasedCandidates.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
    val candidates = groups.ifEmpty {
        releasedCandidates.filter { it.kind == CanonicalSearchKind.RELEASE }
    }
    val knownReleases = candidates.asSequence()
        .sortedWith(
            compareByDescending<CanonicalCatalogSearchRow> { it.releaseDate.orEmpty() }
                .thenBy { it.musicBrainzId },
        )
        .distinctBy { it.musicBrainzId }
        .take(24)
        .mapNotNull { row ->
            canonicalBrowseCardId(row)?.let { id ->
                ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri, null, id)
            }
        }
        .toList()
    // No cached public releases on a fresh installation? Show genuine saved
    // albums rather than a wholly empty feed. Never claim the saved copy is new.
    val releaseItems = if (knownReleases.isNotEmpty()) knownReleases else savedReleases
    if (releaseItems.isNotEmpty()) add(HomeShelf(releasesHeading, releaseItems))

    val localCards = localPicks.asSequence()
        .filter(::isPlayableLocalPick)
        .distinctBy { it.localUri }
        .map { song ->
            ShelfItem(song.title, song.artist, song.thumbnailUrl, null, canonicalLocalPickCardId(song))
        }.toList()
    val serverCards = remotePicks.asSequence()
        .filter(::isTrustedHomeServerAsset)
        .distinctBy { it.providerKey to it.mediaAssetId }
        .map { row ->
            ShelfItem(row.title, row.artistDisplay, row.artworkUri, null, canonicalRemotePickCardId(row))
        }.toList()
    // Sort both sources together, not local-first. The daily rotation picks
    // across real MediaStore/Downloads and synchronized, authorized Server assets.
    val picks = (localCards + serverCards)
        .distinctBy { it.browseId }
        .sortedWith(compareBy<ShelfItem> {
            (it.browseId.orEmpty().hashCode() xor todayIsoDate.hashCode())
        }.thenBy { it.browseId })
        .take(16)
    if (picks.isNotEmpty()) add(HomeShelf(picksHeading, picks))

    val songsByMbid = metadata
        .filter { it.kind == CanonicalSearchKind.RECORDING && it.hasCatalogedAsset }
        .associateBy { it.musicBrainzId }
    val localTrending = trendingLocalPicks.asSequence()
        .filter(::isPlayableLocalPick)
        .distinctBy { it.localUri }
        .take(16)
        .map { song ->
            ShelfItem(song.title, song.artist, song.thumbnailUrl, null, canonicalLocalPickCardId(song))
        }.toList()
    val catalogTrending = trendingRecordingMbids.distinct().asSequence()
        .mapNotNull { songsByMbid[it] }
        .take(16)
        .mapNotNull { row ->
            canonicalBrowseCardId(row)?.let { id ->
                ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri, null, id)
            }
        }.toList()
    val chart = (localTrending + catalogTrending).distinctBy { it.browseId }.take(16)
    if (chart.isNotEmpty()) add(HomeShelf(trendingHeading, chart))
}
