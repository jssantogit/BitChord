package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/** Never treat a playlist/video identifier as a usable local URI. */
internal fun canonicalLocalPickCardId(song: Song): String = "local:pick:${song.videoId}"

internal fun isPlayableLocalPick(song: Song): Boolean =
    song.localUri?.let { it.startsWith("content://") || it.startsWith("file://") } == true

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
): List<HomeShelf> = buildList {
    val groups = metadata.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
    val releases = metadata.filter { it.kind == CanonicalSearchKind.RELEASE }
    // Prefer release groups, which name the musical work. An edition is not a
    // second musical release, and two different MBIDs must never be merged by title.
    val candidates = if (groups.isNotEmpty()) groups else releases
    val datePattern = Regex("^\\d{4}(-\\d{2}(-\\d{2})?)?$")
    val knownReleases = candidates.asSequence()
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

    val picks = localPicks.asSequence()
        .filter(::isPlayableLocalPick)
        .distinctBy { it.localUri }
        .sortedWith(
            compareBy<Song> { it.videoId.hashCode() xor todayIsoDate.hashCode() }
                .thenBy { it.videoId },
        )
        .take(16)
        .map { song ->
            ShelfItem(song.title, song.artist, song.thumbnailUrl, null, canonicalLocalPickCardId(song))
        }
        .toList()
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
