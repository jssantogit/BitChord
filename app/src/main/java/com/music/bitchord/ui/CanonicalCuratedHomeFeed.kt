package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.catalog.metadata.LISTENBRAINZ_PUBLIC_TTL_MS
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRecording
import com.music.bitchord.data.catalog.metadata.publicListenBrainzRecordingRanking
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.PlaybackSourceType
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

/** A MusicBrainz Release Group with Other/unspecified type is NOT an album by default. */
internal fun canonicalMayUseAlbumFallbackLabel(browseId: String): Boolean =
    !browseId.startsWith("catalog:RELEASE_GROUP:") &&
        !browseId.startsWith("catalog:RELEASE:")

/** The editorial album/EP/single shelf must not promote untyped editions or recordings. */
internal fun canonicalEditorialReleaseType(row: CanonicalCatalogSearchRow): String? {
    if (row.kind != CanonicalSearchKind.RELEASE_GROUP && row.kind != CanonicalSearchKind.RELEASE) {
        return null
    }
    return CanonicalEditorialDiscography.classify(
        row.releaseKind, row.secondaryTypes,
    ).format
}

/** Native DetailScreen accepts real browse shelves without synthetic Song.videoId. */
internal fun canonicalNativeSections(
    parentKind: CanonicalSearchKind,
    rows: List<CanonicalCatalogSearchRow>,
    heading: String,
    albumsHeading: String = heading,
    singlesHeading: String = heading,
): List<HomeShelf> {
    val kind = when (parentKind) {
        CanonicalSearchKind.ARTIST -> CanonicalSearchKind.RELEASE_GROUP
        CanonicalSearchKind.RELEASE_GROUP -> CanonicalSearchKind.RELEASE
        else -> return emptyList()
    }
    val eligible = rows.asSequence().filter { it.kind == kind }
        .filter {
            parentKind != CanonicalSearchKind.ARTIST ||
                canonicalEditorialReleaseType(it) != null
        }
        .distinctBy { it.kind to it.musicBrainzId }
        .sortedWith(compareByDescending<CanonicalCatalogSearchRow> {
            it.releaseDate.orEmpty()
        }.thenBy { it.musicBrainzId })
        .toList()
    fun cards(items: List<CanonicalCatalogSearchRow>): List<ShelfItem> = items.map { row ->
        ShelfItem(
            title = row.title,
            subtitle = CanonicalEditorialDiscography.classify(
                row.releaseKind, row.secondaryTypes,
            ).let { decision ->
                if (decision.isPublishable) {
                    (listOfNotNull(decision.format) + decision.secondaryTypes).joinToString(" · ")
                } else row.subtitle.orEmpty()
            },
            thumbnailUrl = row.artworkUri,
            videoId = null,
            browseId = canonicalNativeBrowseId(row),
        )
    }
    if (parentKind == CanonicalSearchKind.ARTIST) {
        return buildList {
            val albums = eligible.filter { canonicalEditorialReleaseType(it) == "Album" }
            val singlesAndEps = eligible.filter { canonicalEditorialReleaseType(it) in setOf("Single", "EP") }
            if (albums.isNotEmpty()) add(HomeShelf(albumsHeading, cards(albums)))
            if (singlesAndEps.isNotEmpty()) add(HomeShelf(singlesHeading, cards(singlesAndEps)))
        }
    }
    return if (eligible.isNotEmpty()) listOf(HomeShelf(heading, cards(eligible))) else emptyList()
}

/**
 * A large artist can have hundreds of cards. Rebuilding and re-sorting both
 * shelves for every CAA completion causes unnecessary allocations and Compose
 * invalidation. Update just the affected artwork/title, retaining the
 * original card and shelf instances when no field changes. Identity stays the
 * actual MusicBrainz ReleaseGroup browse key; no title-based join.
 */
/** Keep a low-resolution CAA Front visible until a verified larger Front
 * arrives; never flash blank or downgrade a higher-quality sleeve. */
internal fun canonicalShouldAcceptArtworkUpdate(old: String?, next: String?): Boolean {
    if (next.isNullOrBlank()) return false
    if (old.isNullOrBlank()) return true
    return canonicalNeedsCoverRefresh(old) && !canonicalNeedsCoverRefresh(next)
}

/**
 * Used ONLY when a newer, already-validated Front-v5 URL for this exact
 * ReleaseGroup comes from CoverArtArchive / trusted Room cache. Old 500px
 * artwork may belong to an earlier chosen edition of the same group; quality
 * alone cannot establish identity. Prefer the verified new Front at equal or
 * higher quality, while forbidding a 500→250 downgrade and redundant repaint.
 *
 * Do not use for provisional edition artwork or unknown remote cover sources.
 */
internal fun canonicalShouldAcceptVerifiedGroupArtwork(
    old: String?, verified: String?,
): Boolean {
    if (verified.isNullOrBlank() || old == verified) return false
    return canonicalShouldAcceptArtworkUpdate(old, verified) ||
        !canonicalNeedsCoverRefresh(verified)
}

/**
 * Convert a trusted Front-v5 Room batch to native artist-shelf patches. A full
 * 500px card is not assumed correct merely because it is already filled.
 * Neither Other/video groups nor unrelated MBIDs can receive the batch image.
 * Keep the currently displayed editorial title instead of resurrecting a
 * stale title captured before the asynchronous cache query finished.
 */
internal fun canonicalArtistVerifiedGroupUpdates(
    rows: List<CanonicalCatalogSearchRow>,
    verifiedGroups: Map<String, String>,
): Map<String, Pair<String, String>> = rows.asSequence()
    .filter {
        it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) != null
    }
    .mapNotNull { row ->
        val url = verifiedGroups[row.musicBrainzId] ?: return@mapNotNull null
        row.musicBrainzId to (url to row.title)
    }
    .toMap()

/**
 * Artist's asynchronous Official-edition fetch paints both native shelf
 * thumbnails and the canonical detail rows. Use the same Front-v5 authority
 * rule on both surfaces: a newly verified 500px Front can supersede an older
 * 500px edition image, but cannot downgrade to a 250px URL.
 */
internal fun canonicalArtistRowsWithOfficialCoverUpdates(
    rows: List<CanonicalCatalogSearchRow>,
    covers: Map<String, Pair<String, String>>,
): List<CanonicalCatalogSearchRow> {
    if (covers.isEmpty()) return rows
    var changed = false
    val updated = rows.map { row ->
        val next = if (row.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(row) != null
        ) covers[row.musicBrainzId] else null
        if (next != null &&
            canonicalShouldAcceptVerifiedGroupArtwork(row.artworkUri, next.first)
        ) {
            changed = true
            row.copy(artworkUri = next.first, title = next.second)
        } else row
    }
    return if (changed) updated else rows
}

internal fun canonicalArtistShelvesWithCoverUpdates(
    sections: List<HomeShelf>,
    covers: Map<String, Pair<String, String>>,
): List<HomeShelf> {
    if (covers.isEmpty()) return sections
    var changedAny = false
    val updated = sections.map { shelf ->
        var changedShelf = false
        val cards = shelf.items.map card@{ item ->
            val browseId = item.browseId.orEmpty()
            if (!browseId.startsWith("catalog:RELEASE_GROUP:")) return@card item
            val groupMbid = browseId.removePrefix("catalog:RELEASE_GROUP:")
            val ready = covers[groupMbid] ?: return@card item
            if (!canonicalShouldAcceptVerifiedGroupArtwork(item.thumbnailUrl, ready.first)) {
                return@card item
            }
            changedShelf = true
            item.copy(title = ready.second, thumbnailUrl = ready.first)
        }
        if (changedShelf) {
            changedAny = true
            shelf.copy(items = cards)
        } else shelf
    }
    return if (changedAny) updated else sections
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
    // A Group Front can be a different edition's video/poster artwork.
    // A specific Release only displays its own typed and approved Front.
    CanonicalSearchKind.RELEASE -> row.musicBrainzId to null
    CanonicalSearchKind.RECORDING -> row.coverReleaseMbid?.let { it to null }
    CanonicalSearchKind.ARTIST -> null
}

/**
 * Old builds persisted 250px CAA URLs. They are safe identity claims, but
 * are not acceptable quality for a hero or a full-width Home release card.
 * Never upgrade a generic image URL by guessing a different provider's asset.
 */
internal fun canonicalNeedsCoverRefresh(artwork: String?): Boolean {
    if (artwork.isNullOrBlank()) return true
    if (!artwork.contains("coverartarchive.org/") && !artwork.contains("archive.org/")) return false
    // CAA commonly serves low-resolution art as /front-250 without a .jpg suffix.
    val path = artwork.substringBefore('?')
    return path.endsWith("-250.jpg") || path.endsWith("-250")
}

/** Bound network enrichment to a small visible subset, with stable identities. */
internal fun canonicalCoverLookupCandidates(
    rows: List<CanonicalCatalogSearchRow>,
    limit: Int,
): List<CanonicalCatalogSearchRow> = rows.asSequence()
    .filter { canonicalNeedsCoverRefresh(it.artworkUri) &&
        canonicalCoverLookupTarget(it) != null }
    .distinctBy { it.kind to it.musicBrainzId }
    .take(limit.coerceAtLeast(0))
    .toList()

/**
 * Search shows recordings before many album cards. Sharing three CAA workers
 * in album-first order left all visible songs waiting behind eight slow metadata
 * calls. Interleave proof-backed candidate types so both start progressively.
 * Reuse of cached album art runs before this bounded network fallback.
 */
internal fun canonicalSearchCoverPriorityQueue(
    rows: List<CanonicalCatalogSearchRow>,
    albumLimit: Int = 8,
    recordingLimit: Int = 12,
): List<CanonicalCatalogSearchRow> {
    val albums = canonicalCoverLookupCandidates(rows.filter {
        it.kind == CanonicalSearchKind.RELEASE ||
            it.kind == CanonicalSearchKind.RELEASE_GROUP
    }, albumLimit)
    val songs = canonicalCoverLookupCandidates(rows.filter {
        it.kind == CanonicalSearchKind.RECORDING
    }, recordingLimit)
    return buildList {
        for (i in 0 until maxOf(songs.size, albums.size)) {
            songs.getOrNull(i)?.let { add(it) }
            albums.getOrNull(i)?.let { add(it) }
        }
    }
}

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
        .filter { row -> canonicalEditorialReleaseType(row) != null }
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

/**
 * Explore is discovery-first, not a second copy of the user's Home.
 *
 * Keep the exact verified release shelf when available. If there is no known
 * published release (offline or cold install), show only one evidence-backed
 * shelf from the existing feed: personal trending, then playable picks. Never
 * manufacture public releases, streams, or a community-wide chart.
 *
 * Home itself is unchanged and continues to include all three editorial rows.
 */
internal fun canonicalExploreShelves(
    homeShelves: List<HomeShelf>,
    releasesHeading: String,
    trendingHeading: String,
): List<HomeShelf> {
    // Home may label saved/downloaded collections as "Lançamentos" when the
    // public catalog is cold. These are real user items, not discovery evidence.
    // Only MusicBrainz release identities can lead Explore's release shelf.
    val releases = homeShelves.firstOrNull { it.title == releasesHeading }
        ?.let { shelf ->
            val verified = shelf.items.filter { item ->
                val id = item.browseId.orEmpty()
                id.startsWith("catalog:RELEASE_GROUP:") || id.startsWith("catalog:RELEASE:")
            }
            if (verified.isEmpty()) null else shelf.copy(items = verified)
        }
    if (releases != null) return listOf(releases)
    val trending = homeShelves.firstOrNull { it.title == trendingHeading && it.items.isNotEmpty() }
    if (trending != null) return listOf(trending)
    return homeShelves.firstOrNull {
        it.title != releasesHeading && it.items.isNotEmpty()
    }?.let { listOf(it) } ?: emptyList()
}

/**
 * Public popularity is optional discovery evidence over KNOWN MusicBrainz
 * recordings. We never fetch by song title, never claim an audio asset, and
 * never treat an all-time community count as a personal or recent chart.
 * Restrict candidate IDs to one bounded batch from the existing catalog.
 */
internal fun canonicalPublicPopularityCandidates(
    rows: List<CanonicalCatalogSearchRow>, limit: Int = 40,
): List<String> = rows.asSequence()
    .filter { it.kind == CanonicalSearchKind.RECORDING && canonicalStreamingTitleIsSafe(it.title) }
    .map { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    .filter { CATALOG_RECORDING_MBID.matches(it) }
    .distinct()
    .take(limit.coerceIn(0, 100))
    .toList()

private val CATALOG_RECORDING_MBID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * A public ListenBrainz observation is NOT a playable track or a release.
 * Cards retain canonical Recording IDs and the existing native click gate,
 * which opens metadata details if the user's library has no verified asset.
 */
internal fun canonicalPublicPopularityShelf(
    rows: List<CanonicalCatalogSearchRow>,
    evidence: List<ListenBrainzPopularityEvidence>,
    heading: String,
    limit: Int = 16,
): HomeShelf? {
    if (heading.isBlank() || limit <= 0) return null
    val recordings = rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.RECORDING &&
            canonicalStreamingTitleIsSafe(it.title) &&
            CATALOG_RECORDING_MBID.matches(it.musicBrainzId.lowercase(java.util.Locale.ROOT)) }
        .distinctBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
        .associateBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    val cards = publicListenBrainzRecordingRanking(evidence)
        .asSequence().filter { it.listeners > 0L && it.listens > 0L }
        .distinctBy { it.recordingMbid }
        .mapNotNull { known ->
            val row = recordings[known.recordingMbid] ?: return@mapNotNull null
            // The ordinary Home card helper intentionally suppresses recordings
            // without audio; this is an Explore metadata-navigation card.
            val browseId = "catalog:RECORDING:${row.musicBrainzId.lowercase(java.util.Locale.ROOT)}"
            ShelfItem(
                title = row.title,
                subtitle = row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = browseId,
            )
        }
        .take(limit.coerceAtMost(16)).toList()
    return cards.takeIf { it.isNotEmpty() }?.let { HomeShelf(heading, it) }
}

/**
 * Public sitewide weekly charts are a different signal from cumulative
 * popularity or the user's personal "Em alta". The upstream sitewide MBID
 * ranks only *already verified catalog rows*; no title/artist guessing,
 * fake playback asset, or album identity is introduced.
 */
internal fun canonicalSitewideCommunityShelf(
    rows: List<CanonicalCatalogSearchRow>,
    rankings: List<ListenBrainzSitewideRecording>,
    heading: String,
    limit: Int = 16,
): HomeShelf? {
    if (heading.isBlank() || limit <= 0) return null
    val catalog = rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.RECORDING &&
            canonicalStreamingTitleIsSafe(it.title) &&
            CATALOG_RECORDING_MBID.matches(it.musicBrainzId.lowercase(java.util.Locale.ROOT)) }
        .distinctBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
        .associateBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    val cards = rankings.asSequence()
        .filter { it.listenCount > 0L }
        .sortedWith(compareByDescending<ListenBrainzSitewideRecording> { it.listenCount }
            .thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid.lowercase(java.util.Locale.ROOT) }
        .mapNotNull { rank ->
            val mbid = rank.recordingMbid.lowercase(java.util.Locale.ROOT)
            val row = catalog[mbid] ?: return@mapNotNull null
            ShelfItem(
                title = row.title,
                subtitle = row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = "catalog:RECORDING:$mbid",
            )
        }.take(limit.coerceAtMost(16)).toList()
    return cards.takeIf { it.isNotEmpty() }?.let { HomeShelf(heading, it) }
}

/**
 * This is a PERSONAL ListenBrainz CF score, never a public listen count.
 * The inputs must already have independently verified MusicBrainz identity,
 * and cards are always metadata-only. No synthesized Song.videoId/MediaAsset.
 */
internal fun canonicalPersonalRecommendationShelf(
    rows: List<CanonicalCatalogSearchRow>,
    rankings: List<com.music.bitchord.data.catalog.metadata.ListenBrainzRecordingRecommendation>,
    heading: String,
    limit: Int = 12,
): HomeShelf? {
    if (heading.isBlank() || limit <= 0) return null
    val validRows = rows.asSequence()
        .filter {
            it.kind == CanonicalSearchKind.RECORDING &&
                canonicalStreamingTitleIsSafe(it.title) &&
                CATALOG_RECORDING_MBID.matches(it.musicBrainzId.lowercase(java.util.Locale.ROOT))
        }.associateBy { it.musicBrainzId.lowercase(java.util.Locale.ROOT) }
    val cards = rankings.asSequence()
        .filter { it.score.isFinite() && it.score > 0.0 }
        .sortedWith(compareByDescending<com.music.bitchord.data.catalog.metadata.ListenBrainzRecordingRecommendation> { it.score }
            .thenBy { it.recordingMbid })
        .distinctBy { it.recordingMbid.lowercase(java.util.Locale.ROOT) }
        .mapNotNull { rank ->
            val id = rank.recordingMbid.lowercase(java.util.Locale.ROOT)
            val row = validRows[id] ?: return@mapNotNull null
            ShelfItem(
                title = row.title,
                subtitle = row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = "catalog:RECORDING:$id",
            )
        }.take(limit.coerceAtMost(12)).toList()
    return cards.takeIf(List<ShelfItem>::isNotEmpty)?.let { HomeShelf(heading, it) }
}

/**
 * Public ListenBrainz discovery cards are navigation-only, even when the
 * same Recording also happens to have a local/server MediaAsset. This keeps
 * aggregate rankings distinct from the user's playback intent.
 */
internal fun canonicalCommunityCardIsMetadataOnly(
    tabOrigin: PlaybackSourceType,
    shelfTitle: String,
    communityHeading: String,
    browseId: String?,
    allowHome: Boolean = false,
): Boolean {
    if ((tabOrigin != PlaybackSourceType.EXPLORE &&
            !(allowHome && tabOrigin == PlaybackSourceType.HOME)) ||
        shelfTitle != communityHeading || browseId == null
    ) return false
    val prefix = "catalog:RECORDING:"
    return browseId.startsWith(prefix, ignoreCase = true) &&
        CATALOG_RECORDING_MBID.matches(
            browseId.substring(prefix.length).lowercase(java.util.Locale.ROOT),
        )
}

/**
 * Resolve an editorial card by an exact typed catalog identity. In Explore,
 * metadata-only public Recording cards navigate to detail, not playback.
 * Missing browse keys and inferred/title-only joins are never accepted.
 */
internal fun canonicalEditorialRowForCard(
    rows: List<CanonicalCatalogSearchRow>,
    browseId: String?,
): CanonicalCatalogSearchRow? {
    if (browseId.isNullOrBlank()) return null
    return rows.firstOrNull { row ->
        val exactId = when (row.kind) {
            CanonicalSearchKind.RECORDING -> if (CATALOG_RECORDING_MBID.matches(row.musicBrainzId.lowercase(java.util.Locale.ROOT))) {
                "catalog:RECORDING:${row.musicBrainzId.lowercase(java.util.Locale.ROOT)}"
            } else null
            CanonicalSearchKind.RELEASE, CanonicalSearchKind.RELEASE_GROUP ->
                canonicalBrowseCardId(row)
            CanonicalSearchKind.ARTIST -> null
        }
        exactId != null && exactId.equals(browseId, ignoreCase = true)
    }
}

/**
 * Home and Explore share rendering but never borrow one another's popularity
 * claims. Verified releases lead; public all-time popularity is a distinct
 * discovery section. If no public evidence exists, the existing personal
 * fallback remains available offline.
 */
internal fun canonicalExploreWithCommunityShelves(
    homeShelves: List<HomeShelf>,
    releasesHeading: String,
    trendingHeading: String,
    community: HomeShelf?,
): List<HomeShelf> {
    val known = canonicalExploreShelves(homeShelves, releasesHeading, trendingHeading)
    val public = community?.takeIf { it.items.isNotEmpty() && it.title.isNotBlank() }
        ?: return known
    return if (known.firstOrNull()?.title == releasesHeading) known + public else listOf(public)
}

/**
 * View-state freshness follows the same six-hour budget as the public client.
 * Re-entering Explore with an unchanged MBID batch should not flash an empty
 * shelf or needlessly schedule a second request. Unknown rows are cached too.
 */
internal fun canonicalPublicPopularityCacheIsFresh(
    requested: List<String>,
    previous: List<String>?,
    fetchedAtMs: Long?,
    nowMs: Long,
): Boolean = requested.isNotEmpty() && requested == previous &&
    fetchedAtMs != null && nowMs >= fetchedAtMs &&
    nowMs - fetchedAtMs < LISTENBRAINZ_PUBLIC_TTL_MS

/** Feed sections share rendering, but listening events retain their actual tab provenance. */
internal fun canonicalEditorialPlaybackSource(explore: Boolean): PlaybackSourceType =
    if (explore) PlaybackSourceType.EXPLORE else PlaybackSourceType.HOME
