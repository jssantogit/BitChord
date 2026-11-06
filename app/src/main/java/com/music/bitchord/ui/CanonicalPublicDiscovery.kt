package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.time.LocalDate
import java.util.Locale

/**
 * Public release discovery is independent of the user's indexed recordings.
 * MusicBrainz is metadata only; these entries never create Song or MediaAsset.
 */
internal fun canonicalPublicReleaseRows(
    hits: List<MusicBrainzReleaseGroupHit>,
    fromIsoDate: String,
    throughIsoDate: String,
): List<CanonicalCatalogSearchRow> = hits.asSequence()
    .filter { hit ->
        val date = hit.firstReleaseDate.orEmpty()
        date.length == 10 && date >= fromIsoDate && date <= throughIsoDate &&
            runCatching { LocalDate.parse(date) }.isSuccess &&
            !hit.artistDisplay.isNullOrBlank() &&
            canonicalStreamingTitleIsSafe(hit.title) &&
            CanonicalEditorialDiscography.classify(
                hit.primaryType, hit.secondaryTypes,
            ).isPublishable
    }
    .distinctBy { it.musicBrainzId.lowercase(Locale.ROOT) }
    .sortedWith(compareByDescending<MusicBrainzReleaseGroupHit> {
        it.firstReleaseDate.orEmpty()
    }.thenBy { it.musicBrainzId })
    .map { hit ->
        CanonicalCatalogSearchRow(
            musicBrainzId = hit.musicBrainzId.lowercase(Locale.ROOT),
            kind = CanonicalSearchKind.RELEASE_GROUP,
            title = hit.title,
            subtitle = hit.artistDisplay,
            releaseDate = hit.firstReleaseDate,
            releaseKind = hit.primaryType,
            secondaryTypes = hit.secondaryTypes,
            artworkUri = hit.artworkUri,
        )
    }
    .take(40)
    .toList()

/**
 * Editorial variety is a daily rotation of actual public releases, not a
 * fabricated recommendation or a claim about listeners' popularity.
 */
internal fun canonicalDailyPublicPicks(
    releases: List<CanonicalCatalogSearchRow>,
    todayIsoDate: String,
    limit: Int = 12,
): List<ShelfItem> =
    releases.asSequence()
        .filter { it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) != null }
        .sortedWith(compareBy<CanonicalCatalogSearchRow> {
            (it.musicBrainzId + todayIsoDate).hashCode()
        }.thenBy { it.musicBrainzId })
        .take(limit.coerceIn(0, 24))
        .map {
            ShelfItem(it.title, it.subtitle.orEmpty(), it.artworkUri,
                null, canonicalNativeBrowseId(it))
        }.toList()

/** Static taxonomy is navigation, not invented audio, release, or chart rows. */
internal data class CanonicalExploreGenre(val tag: String, val title: String)

internal val canonicalExploreGenres = listOf(
    CanonicalExploreGenre("pop", "Pop"),
    CanonicalExploreGenre("rock", "Rock"),
    CanonicalExploreGenre("hip-hop", "Hip-hop"),
    CanonicalExploreGenre("electronic", "Eletrônica"),
    CanonicalExploreGenre("k-pop", "K-pop"),
    CanonicalExploreGenre("rnb", "R&B"),
    CanonicalExploreGenre("jazz", "Jazz"),
    CanonicalExploreGenre("metal", "Metal"),
    CanonicalExploreGenre("classical", "Clássica"),
    CanonicalExploreGenre("folk", "Folk"),
    CanonicalExploreGenre("ambient", "Ambient"),
    CanonicalExploreGenre("punk", "Punk"),
)

internal fun canonicalExploreGenreForBrowseId(browseId: String?): CanonicalExploreGenre? =
    canonicalExploreGenres.firstOrNull { browseId == "catalog:GENRE:${it.tag}" }

internal fun canonicalExploreGenreShelf(heading: String): HomeShelf =
    HomeShelf(heading, canonicalExploreGenres.map { genre ->
        ShelfItem(
            title = genre.title,
            subtitle = "",
            thumbnailUrl = null,
            videoId = null,
            browseId = "catalog:GENRE:${genre.tag}",
        )
    })

internal fun canonicalPublicGenreReleaseShelf(
    genre: CanonicalExploreGenre,
    rows: List<CanonicalCatalogSearchRow>,
): HomeShelf? = rows.takeIf { it.isNotEmpty() }?.let { available ->
    HomeShelf("Lançamentos · ${genre.title}", available.map { row ->
        ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri, null,
            canonicalNativeBrowseId(row))
    })
}

/**
 * Apple's genre editorial rhythm on the existing native HomeScreen, but only
 * using real MusicBrainz ReleaseGroup identities. No playlists, stations,
 * top-songs or popularity rankings can be inferred from genre tags alone.
 *
 * Each card occurs in exactly one shelf. A deterministic daily highlight order
 * provides variety without telling the listener it is a measured chart.
 */
internal fun canonicalGenreEditorialShelves(
    genre: CanonicalExploreGenre,
    rows: List<CanonicalCatalogSearchRow>,
    todayIsoDate: String,
): List<HomeShelf> {
    val eligible = rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) != null &&
            !it.subtitle.isNullOrBlank() &&
            canonicalStreamingTitleIsSafe(it.title) &&
            (it.releaseDate?.let { date -> date <= todayIsoDate } ?: true) }
        .distinctBy { it.musicBrainzId.lowercase(Locale.ROOT) }
        .sortedWith(compareByDescending<CanonicalCatalogSearchRow> { it.releaseDate.orEmpty() }
            .thenBy { it.musicBrainzId })
        .toList()
    if (eligible.isEmpty()) return emptyList()
    // Allocate with stable ownership; never depend on title-only album matches.
    val assigned = mutableSetOf<String>()
    fun section(heading: String, candidates: List<CanonicalCatalogSearchRow>, limit: Int): HomeShelf? {
        val selected = candidates.asSequence()
            .filter { it.musicBrainzId.lowercase(Locale.ROOT) !in assigned }
            .take(limit)
            .toList()
        selected.forEach { assigned += it.musicBrainzId.lowercase(Locale.ROOT) }
        return selected.takeIf { it.isNotEmpty() }?.let { tracks ->
            HomeShelf(heading, tracks.map { row ->
                ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri, null,
                    canonicalNativeBrowseId(row))
            })
        }
    }
    val freshSince = runCatching {
        LocalDate.parse(todayIsoDate).minusDays(180).toString()
    }.getOrDefault(todayIsoDate)
    val featured = eligible.sortedWith(
        compareByDescending<CanonicalCatalogSearchRow> { !it.artworkUri.isNullOrBlank() }
            .thenBy { (it.musicBrainzId + todayIsoDate).hashCode() }
            .thenBy { it.musicBrainzId },
    )
    return buildList {
        section("Para descobrir · ${genre.title}", featured, 4)?.let(::add)
        section("Lançamentos · ${genre.title}", eligible.filter {
            it.releaseDate?.let { date ->
                date.length == 10 && date >= freshSince
            } == true
        }, 12)?.let(::add)
        section("Álbuns · ${genre.title}", eligible.filter {
            canonicalEditorialReleaseType(it) == "Album"
        }, 16)?.let(::add)
        section("Singles e EPs · ${genre.title}", eligible.filter {
            canonicalEditorialReleaseType(it) in setOf("Single", "EP")
        }, 16)?.let(::add)
        section("Mais para explorar · ${genre.title}", eligible, 16)?.let(::add)
    }
}

/** Independent public metadata never becomes a playable song without an asset. */
internal fun canonicalHomeWithWorldwideEditorial(
    localShelves: List<HomeShelf>,
    worldwideReleases: List<CanonicalCatalogSearchRow>,
    releasesHeading: String,
    picksHeading: String,
    trendingHeading: String,
    worldwideTrending: HomeShelf?,
    todayIsoDate: String,
    albumsHeading: String = "Álbuns",
    singlesHeading: String = "Singles e EPs",
    moreHeading: String = "Mais",
    community: HomeShelf? = null,
    personal: HomeShelf? = null,
): List<HomeShelf> {
    val shelves = localShelves.toMutableList()
    // Remove visual duplicates from an editorial feed, not from the canonical
    // catalog. Different MusicBrainz editions keep their real MBIDs in search.
    val publicRows = worldwideReleases.asSequence()
        .filter { it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) != null }
        .distinctBy { it.musicBrainzId.lowercase(Locale.ROOT) }
        .sortedWith(
            compareByDescending<CanonicalCatalogSearchRow> { it.releaseDate.orEmpty() }
                .thenByDescending { !it.artworkUri.isNullOrBlank() }
                .thenBy { it.musicBrainzId },
        )
        .distinctBy { (it.title.trim() + "|" + it.subtitle.orEmpty().trim()).lowercase(Locale.ROOT) }
        .toList()
    val selected = mutableSetOf<String>()
    fun item(row: CanonicalCatalogSearchRow): ShelfItem =
        ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri,
            null, canonicalNativeBrowseId(row))
    fun unique(cards: List<ShelfItem>): List<ShelfItem> = cards.filter { card ->
        val id = card.browseId ?: return@filter false
        selected.add(id)
    }
    if (publicRows.isNotEmpty()) {
        // Reserve enough releases for separately curated shelves instead of
        // using all 24 on the hero and repeating them under Quick Picks.
        val launchCount = if (publicRows.size <= 10)
            maxOf(1, publicRows.size / 2) else minOf(10, publicRows.size / 3)
        val launches = unique(publicRows.take(launchCount).map(::item))
        val index = shelves.indexOfFirst { it.title == releasesHeading }
        if (index >= 0) shelves[index] = shelves[index].copy(items = launches)
        else shelves.add(0, HomeShelf(releasesHeading, launches))
    } else {
        // On a cold offline install, retain only genuinely available local
        // items. Never present a saved album as a verified worldwide launch.
        shelves.firstOrNull { it.title == releasesHeading }?.items?.forEach {
            it.browseId?.let(selected::add)
        }
    }

    val picksIndex = shelves.indexOfFirst { it.title == picksHeading }
    val existingPicks = shelves.getOrNull(picksIndex).takeIf { picksIndex >= 0 }?.items.orEmpty()
    val availablePicks = canonicalDailyPublicPicks(
        publicRows.filterNot { canonicalNativeBrowseId(it) in selected },
        todayIsoDate, limit = 24,
    ).sortedWith(compareByDescending<ShelfItem> { !it.thumbnailUrl.isNullOrBlank() })
    val picks = unique((existingPicks + availablePicks).distinctBy { it.browseId }.take(16))
    if (picksIndex >= 0) {
        if (picks.isNotEmpty()) shelves[picksIndex] = shelves[picksIndex].copy(items = picks)
        else shelves.removeAt(picksIndex)
    } else if (picks.isNotEmpty()) shelves.add(minOf(1, shelves.size), HomeShelf(picksHeading, picks))

    // "Em alta" is ONLY upstream ListenBrainz evidence or verified local
    // listening evidence already present. Recent release dates are not charts.
    if (worldwideTrending != null && worldwideTrending.items.isNotEmpty()) {
        val index = shelves.indexOfFirst { it.title == trendingHeading }
        if (index >= 0) shelves[index] = worldwideTrending
        else shelves.add(minOf(2, shelves.size), worldwideTrending)
    }
    // All-time community observations remain useful discovery evidence, but
    // must NEVER masquerade as a current weekly "Em alta" chart. If a fresh
    // weekly chart is available, don't render the same ranked songs twice.
    if (worldwideTrending == null && community?.items?.isNotEmpty() == true &&
        community.title.isNotBlank()
    ) shelves.add(minOf(2, shelves.size), community)
    fun appendPublic(heading: String, rows: List<CanonicalCatalogSearchRow>) {
        val cards = unique(rows.map(::item).take(12))
        if (cards.isNotEmpty()) shelves.add(HomeShelf(heading, cards))
    }
    val unused = publicRows.filterNot { canonicalNativeBrowseId(it) in selected }
    appendPublic(albumsHeading, unused.filter { canonicalEditorialReleaseType(it) == "Album" })
    val unusedSingles = publicRows.filterNot { canonicalNativeBrowseId(it) in selected }
    appendPublic(singlesHeading, unusedSingles.filter {
        canonicalEditorialReleaseType(it) in setOf("EP", "Single")
    })
    appendPublic(moreHeading, publicRows.filterNot { canonicalNativeBrowseId(it) in selected })
    // A personal, account-verified ListenBrainz recommendation is not a genre
    // or public popularity claim. Never put it in Explore's taxonomy grid.
    personal?.takeIf { it.title.isNotBlank() && it.items.isNotEmpty() }?.let { personalShelf ->
        selected.addAll(shelves.flatMap { shelf -> shelf.items.mapNotNull { it.browseId } })
        val uniquePersonal = personalShelf.items.filter { item ->
            val id = item.browseId ?: return@filter false
            selected.add(id)
        }
        if (uniquePersonal.isNotEmpty()) {
            shelves.add(personalShelf.copy(items = uniquePersonal))
        }
    }

    return if (publicRows.isEmpty() && worldwideTrending == null) shelves
        else shelves.filter { it.items.isNotEmpty() }
}


/**
 * Genres belong to Explore's first viewport, not beyond the community and
 * personal feeds. Keep releases first when genuinely public; otherwise make
 * categories the first shelf. The existing native HomeScreen renders all cards.
 */
internal fun canonicalExploreDiscoveryShelves(
    editorial: List<HomeShelf>,
    releasesHeading: String,
    genres: HomeShelf,
    personal: HomeShelf?,
): List<HomeShelf> {
    val lead = editorial.firstOrNull()
    val hasPublicReleaseLead = lead != null && lead.title == releasesHeading &&
        lead.items.isNotEmpty() && lead.items.all { item ->
            val id = item.browseId.orEmpty()
            id.startsWith("catalog:RELEASE_GROUP:") ||
                id.startsWith("catalog:RELEASE:")
        }
    return buildList {
        if (hasPublicReleaseLead && lead != null) add(lead)
        add(genres)
        addAll(if (hasPublicReleaseLead) editorial.drop(1) else editorial)
        personal?.takeIf { it.items.isNotEmpty() }?.let(::add)
    }
}
