package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
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
): List<CanonicalCatalogSearchRow> = canonicalPublicTypedReleaseRows(hits) { date ->
    date != null && date.length == 10 && date >= fromIsoDate &&
        date <= throughIsoDate && runCatching { LocalDate.parse(date) }.isSuccess
}

/**
 * A genre is a historical browse category, NOT a world new-release chart.
 * MusicBrainz can know a real ReleaseGroup and artist credit while only knowing
 * its year/month, or no release date at all. Keep that incomplete evidence
 * unchanged instead of hiding the album or inventing a day of release.
 */
internal fun canonicalPublicGenreRows(
    hits: List<MusicBrainzReleaseGroupHit>,
    throughIsoDate: String,
): List<CanonicalCatalogSearchRow> {
    val today = runCatching { LocalDate.parse(throughIsoDate) }.getOrNull()
        ?: return emptyList()
    return canonicalPublicTypedReleaseRows(hits) { date ->
        when (date?.length) {
            null, 0 -> true
            4 -> runCatching { java.time.Year.parse(date).value <= today.year }
                .getOrDefault(false)
            7 -> runCatching {
                java.time.YearMonth.parse(date) <= java.time.YearMonth.from(today)
            }.getOrDefault(false)
            10 -> runCatching { LocalDate.parse(date) <= today }
                .getOrDefault(false)
            else -> false
        }
    }
}

/** Common type, identity and credit gate for world and genre discovery. */
private fun canonicalPublicTypedReleaseRows(
    hits: List<MusicBrainzReleaseGroupHit>,
    acceptsDate: (String?) -> Boolean,
): List<CanonicalCatalogSearchRow> = hits.asSequence()
    .filter { hit ->
        acceptsDate(hit.firstReleaseDate) &&
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

internal fun canonicalExploreGenreShelf(
    heading: String,
    verifiedCovers: Map<String, String> = emptyMap(),
): HomeShelf =
    HomeShelf(heading, canonicalExploreGenres.map { genre ->
        ShelfItem(
            title = genre.title,
            subtitle = "",
            thumbnailUrl = verifiedCovers[genre.tag],
            videoId = null,
            browseId = "catalog:GENRE:${genre.tag}",
        )
    })

/** Public playlists use their own ListenBrainz MBID, never ReleaseGroup identity. */
internal fun canonicalPublicPlaylistShelf(
    heading: String,
    playlists: List<ListenBrainzPublicPlaylist>,
): HomeShelf? = playlists.takeIf { it.isNotEmpty() }?.let { entries ->
    HomeShelf(heading, entries.distinctBy { it.mbid }.take(12).map { row ->
        ShelfItem(
            title = row.title,
            subtitle = row.creator,
            thumbnailUrl = row.artworkUrl,
            videoId = null,
            browseId = row.browseId,
        )
    })
}

internal fun canonicalPublicGenreReleaseShelf(
    genre: CanonicalExploreGenre,
    rows: List<CanonicalCatalogSearchRow>,
): HomeShelf? = rows.takeIf { it.isNotEmpty() }?.let { available ->
    HomeShelf("Lançamentos", available.map { row ->
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
    playlists: List<ListenBrainzPublicPlaylist> = emptyList(),
): List<HomeShelf> {
    val playlistShelf = canonicalPublicPlaylistShelf("Playlists", playlists)
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
    if (eligible.isEmpty()) return listOfNotNull(playlistShelf)
    // Reserve at least one genuine entry for each evidence-backed category
    // before the hero uses all the limited results from a sparse tag search.
    // Every MusicBrainz ReleaseGroup still appears in at most one section.
    val assigned = mutableSetOf<String>()
    fun take(candidates: List<CanonicalCatalogSearchRow>, limit: Int): List<CanonicalCatalogSearchRow> {
        val picked = candidates.asSequence()
            .filter { it.musicBrainzId.lowercase(Locale.ROOT) !in assigned }
            .take(limit).toList()
        picked.forEach { assigned += it.musicBrainzId.lowercase(Locale.ROOT) }
        return picked
    }
    fun shelf(title: String, rows: List<CanonicalCatalogSearchRow>): HomeShelf? =
        rows.takeIf { it.isNotEmpty() }?.let { available ->
            HomeShelf(title, available.map { row ->
                ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri, null,
                    canonicalNativeBrowseId(row))
            })
        }

    val freshSince = runCatching {
        LocalDate.parse(todayIsoDate).minusDays(180).toString()
    }.getOrDefault(todayIsoDate)
    val fresh = eligible.filter { row ->
        row.releaseDate?.let { it.length == 10 && it >= freshSince } == true
    }
    val albums = eligible.filter { canonicalEditorialReleaseType(it) == "Album" }
    val singles = eligible.filter {
        canonicalEditorialReleaseType(it) in setOf("Single", "EP")
    }
    val featured = eligible.sortedWith(
        compareByDescending<CanonicalCatalogSearchRow> { !it.artworkUri.isNullOrBlank() }
            .thenBy { (it.musicBrainzId + todayIsoDate).hashCode() }
            .thenBy { it.musicBrainzId },
    )
    val launchSeed = take(fresh, 1)
    val albumSeed = take(albums, 1)
    val singleSeed = take(singles, 1)
    val discoveries = take(featured, if (eligible.size >= 12) 4 else 2)
    val launches = launchSeed + take(fresh, 11)
    val albumShelf = albumSeed + take(albums, 15)
    val singleShelf = singleSeed + take(singles, 15)
    val more = take(eligible, 16)
    // The detail heading already names the genre: don't append ' · Rock'
    // to each shelf. Missing evidence omits that shelf, not a fake release.
    return listOfNotNull(
        shelf("Para descobrir", discoveries),
        shelf("Lançamentos", launches),
        playlistShelf,
        shelf("Álbuns", albumShelf),
        shelf("Singles e EPs", singleShelf),
        shelf("Mais para explorar", more),
    )
}

/**
 * Keep the historical MusicBrainz shelves and independent ListenBrainz
 * playlists together without inflating BitChordApp's Compose method.
 * Failure of one remote source cannot blank verified rows from the other.
 */
internal fun canonicalGenreDisplayState(
    genre: CanonicalExploreGenre?,
    current: com.music.bitchord.data.model.UiState<List<CanonicalCatalogSearchRow>>,
    playlists: List<ListenBrainzPublicPlaylist>,
    todayIsoDate: String,
): com.music.bitchord.data.model.UiState<List<HomeShelf>> {
    val playlist = canonicalPublicPlaylistShelf("Playlists", playlists)
    return when (current) {
        com.music.bitchord.data.model.UiState.Loading ->
            if (playlist != null) com.music.bitchord.data.model.UiState.Success(listOf(playlist))
            else com.music.bitchord.data.model.UiState.Loading
        is com.music.bitchord.data.model.UiState.Error ->
            if (playlist != null) com.music.bitchord.data.model.UiState.Success(listOf(playlist))
            else current
        is com.music.bitchord.data.model.UiState.Success -> {
            val shelves = genre?.let {
                canonicalGenreEditorialShelves(it, current.data, todayIsoDate, playlists)
            }.orEmpty()
            if (shelves.isEmpty()) {
                com.music.bitchord.data.model.UiState.Error(
                    "Não encontramos lançamentos verificados para este gênero.",
                )
            } else com.music.bitchord.data.model.UiState.Success(shelves)
        }
    }
}

/** One small release page is not enough to populate a diverse public Home.
 * Prefer broadening the real MusicBrainz query before claiming the feed is complete. */
internal fun canonicalPublicNeedsMoreEditorialRows(
    rows: List<CanonicalCatalogSearchRow>,
): Boolean {
    val typed = rows.filter {
        it.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(it) != null
    }
    return typed.size < 30 ||
        typed.none { canonicalEditorialReleaseType(it) == "Album" } ||
        typed.none { canonicalEditorialReleaseType(it) in setOf("Single", "EP") }
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
    publicPlaylists: HomeShelf? = null,
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
    // Give each real editorial format a minimum allocation before the Home
    // hero and daily picks use the limited first public result page.
    // If the verified ListenBrainz chart is missing, a distinct 'Mais' shelf
    // can complete five sections without pretending to be 'Em alta'.
    val supportsFive = publicRows.size >= 5
    val reservedAlbum = if (supportsFive) publicRows.asReversed().firstOrNull {
        canonicalEditorialReleaseType(it) == "Album"
    } else null
    val reservedSingle = if (supportsFive) publicRows.asReversed().firstOrNull {
        canonicalEditorialReleaseType(it) in setOf("Single", "EP")
    } else null
    val hasRealChartOrCommunity = worldwideTrending?.items?.isNotEmpty() == true ||
        community?.items?.isNotEmpty() == true
    val reservedMore = if (supportsFive && !hasRealChartOrCommunity) {
        publicRows.asReversed().firstOrNull {
            it.musicBrainzId != reservedAlbum?.musicBrainzId &&
                it.musicBrainzId != reservedSingle?.musicBrainzId
        }
    } else null
    val reservedIds = listOfNotNull(reservedAlbum, reservedSingle, reservedMore)
        .map(::canonicalNativeBrowseId).toSet()
    if (publicRows.isNotEmpty()) {
        // Reserve enough releases for separately curated shelves instead of
        // using all 24 on the hero and repeating them under Quick Picks.
        val launchCount = if (publicRows.size <= 10)
            maxOf(1, publicRows.size / 2) else minOf(10, publicRows.size / 3)
        val launches = unique(publicRows.filterNot {
            canonicalNativeBrowseId(it) in reservedIds
        }.take(launchCount).map(::item))
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
        publicRows.filterNot {
            canonicalNativeBrowseId(it) in selected ||
                canonicalNativeBrowseId(it) in reservedIds
        },
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
    // Home is a global discovery feed, not the genre's format-by-format
    // discography. Real public playlists replace the genre-like singles row.
    publicPlaylists?.takeIf { it.items.isNotEmpty() }?.let { shelf ->
        shelves.add(minOf(3, shelves.size), shelf)
    }
    fun appendPublic(heading: String, rows: List<CanonicalCatalogSearchRow>) {
        val cards = unique(rows.map(::item).take(12))
        if (cards.isNotEmpty()) shelves.add(HomeShelf(heading, cards))
    }
    val unused = publicRows.filterNot {
        canonicalNativeBrowseId(it) in selected ||
            it.musicBrainzId == reservedMore?.musicBrainzId
    }
    appendPublic(albumsHeading,
        listOfNotNull(reservedAlbum) + unused.filter { canonicalEditorialReleaseType(it) == "Album" })
    val unusedSingles = publicRows.filterNot {
        canonicalNativeBrowseId(it) in selected ||
            it.musicBrainzId == reservedMore?.musicBrainzId
    }
    if (publicPlaylists?.items.isNullOrEmpty()) {
        appendPublic(singlesHeading,
            listOfNotNull(reservedSingle) + unusedSingles.filter {
                canonicalEditorialReleaseType(it) in setOf("EP", "Single")
            })
    }
    appendPublic(moreHeading,
        listOfNotNull(reservedMore) + publicRows.filterNot { canonicalNativeBrowseId(it) in selected })
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
