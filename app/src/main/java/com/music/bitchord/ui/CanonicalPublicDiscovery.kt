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
            subtitle = "MusicBrainz",
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

/** Keep the native Home shelves, filling gaps with verified worldwide metadata. */
internal fun canonicalHomeWithWorldwideEditorial(
    localShelves: List<HomeShelf>,
    worldwideReleases: List<CanonicalCatalogSearchRow>,
    releasesHeading: String,
    picksHeading: String,
    trendingHeading: String,
    worldwideTrending: HomeShelf?,
    todayIsoDate: String,
): List<HomeShelf> {
    val shelves = localShelves.toMutableList()
    // Lançamentos must work on a completely empty installation: MusicBrainz
    // public ReleaseGroup identities are enough for metadata-only navigation.
    // Never label a user's old download as a *new worldwide* release when the
    // public feed has verified current releases available.
    val publicReleases = worldwideReleases.asSequence()
        .filter {
            it.kind == CanonicalSearchKind.RELEASE_GROUP &&
                canonicalEditorialReleaseType(it) != null
        }
        .distinctBy { it.musicBrainzId.lowercase(Locale.ROOT) }
        .sortedWith(compareByDescending<CanonicalCatalogSearchRow> {
            it.releaseDate.orEmpty()
        }.thenBy { it.musicBrainzId })
        .take(24)
        .map { row ->
            ShelfItem(row.title, row.subtitle.orEmpty(), row.artworkUri,
                null, canonicalNativeBrowseId(row))
        }
        .toList()
    if (publicReleases.isNotEmpty()) {
        val index = shelves.indexOfFirst { it.title == releasesHeading }
        if (index >= 0) {
            shelves[index] = shelves[index].copy(items = publicReleases)
        } else {
            shelves.add(0, HomeShelf(releasesHeading, publicReleases))
        }
    }
    val daily = canonicalDailyPublicPicks(worldwideReleases, todayIsoDate)
    if (daily.isNotEmpty()) {
        val index = shelves.indexOfFirst { it.title == picksHeading }
        if (index >= 0) {
            val original = shelves[index]
            shelves[index] = original.copy(
                items = (original.items + daily)
                    .distinctBy { it.browseId }.take(16),
            )
        } else {
            shelves.add(minOf(1, shelves.size), HomeShelf(picksHeading, daily))
        }
    }
    // World chart claims are always tied to ListenBrainz evidence. A local
    // 'Em alta' row remains a valid offline fallback when no chart is current.
    if (worldwideTrending != null && worldwideTrending.items.isNotEmpty()) {
        val index = shelves.indexOfFirst { it.title == trendingHeading }
        if (index >= 0) shelves[index] = worldwideTrending
        else shelves.add(worldwideTrending)
    }
    return shelves
}


/**
 * Genres belong to Explore's first viewport, not beyond the community and
 * personal feeds. Keep releases first when genuinely public; otherwise make
 * categories the first shelf. The existing native HomeScreen renders all cards.
 */
internal fun canonicalExploreDiscoveryShelves(
    editorial: List<HomeShelf>,
    genres: HomeShelf,
    personal: HomeShelf?,
): List<HomeShelf> {
    val lead = editorial.firstOrNull()
    val hasPublicReleaseLead = lead?.items?.any { item ->
        val id = item.browseId.orEmpty()
        id.startsWith("catalog:RELEASE_GROUP:") ||
            id.startsWith("catalog:RELEASE:")
    } == true
    return buildList {
        if (hasPublicReleaseLead && lead != null) add(lead)
        add(genres)
        addAll(if (hasPublicReleaseLead) editorial.drop(1) else editorial)
        personal?.takeIf { it.items.isNotEmpty() }?.let(::add)
    }
}
