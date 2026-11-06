package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.canonicalExploreDiscoveryShelves
import com.music.bitchord.ui.canonicalDailyPublicPicks
import com.music.bitchord.ui.canonicalExploreGenreForBrowseId
import com.music.bitchord.ui.canonicalExploreGenreShelf
import com.music.bitchord.ui.canonicalExploreGenres
import com.music.bitchord.ui.canonicalHomeWithWorldwideEditorial
import com.music.bitchord.ui.canonicalPublicReleaseRows
import com.music.bitchord.ui.canonicalPublicGenreReleaseShelf
import com.music.bitchord.ui.canonicalGenreEditorialShelves
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalPublicDiscoveryTest {
    private val recent = MusicBrainzReleaseGroupHit(
        "03f3f1f2-12a4-4e47-9d52-459ee4a4f0ce", "Verified release",
        "Album", "2026-11-01", "Artist", null,
    )
    private val another = MusicBrainzReleaseGroupHit(
        "08f3f1f2-12a4-4e47-9d52-459ee4a4f0ce", "Verified single",
        "Single", "2026-10-28", "Another artist", null,
    )

    @Test fun coldInstallCanShowRealPublicReleasesWithoutAnySavedMetadata() {
        val rows = canonicalPublicReleaseRows(
            listOf(recent, another), "2026-10-01", "2026-11-05",
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.kind == CanonicalSearchKind.RELEASE_GROUP })
        val picks = canonicalDailyPublicPicks(rows, "2026-11-05")
        assertEquals(2, picks.size)
        assertTrue(picks.all { it.videoId == null &&
            it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") })
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), rows, "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-05",
        )
        assertEquals(listOf("Lançamentos", "Escolha a dedo"), home.map { it.title })
        assertEquals("catalog:RELEASE_GROUP:${recent.musicBrainzId}", home.first().items.first().browseId)
        assertTrue(home.flatMap { it.items }.all { it.videoId == null })
    }

    @Test fun aNonemptyRawShortWindowCanStillHaveNoEligiblePublicRows() {
        // The worldwide loader must inspect verified rows, not just whether
        // MusicBrainz returned a nonempty raw page before trying 90 days.
        val uncredited = recent.copy(artistDisplay = null)
        val first = canonicalPublicReleaseRows(
            listOf(uncredited), "2026-10-06", "2026-11-05",
        )
        assertTrue(first.isEmpty())
        val expanded = canonicalPublicReleaseRows(
            listOf(another), "2026-08-07", "2026-11-05",
        )
        assertEquals(listOf(another.musicBrainzId), expanded.map { it.musicBrainzId })
    }

    @Test fun disallowFutureUncreditedUntypedOrVideoEditionsFromPublicReleases() {
        val rows = canonicalPublicReleaseRows(
            listOf(
                recent,
                recent.copy(musicBrainzId = "bbbbbbbb-1111-2222-3333-444444444444",
                    firstReleaseDate = "2027-01-01"),
                recent.copy(musicBrainzId = "cccccccc-1111-2222-3333-444444444444",
                    artistDisplay = null),
                recent.copy(musicBrainzId = "dddddddd-1111-2222-3333-444444444444",
                    primaryType = "Other"),
                recent.copy(musicBrainzId = "eeeeeeee-1111-2222-3333-444444444444",
                    title = "Dance Practice Version"),
            ),
            "2026-10-01", "2026-11-05",
        )
        assertEquals(listOf(recent.musicBrainzId), rows.map { it.musicBrainzId })
    }

    @Test fun publicReleasesReplaceStaleLocalLaunchShelfButOfflineLocalFallbackSurvives() {
        val stale = HomeShelf("Lançamentos", listOf(
            ShelfItem("Downloaded album", "Offline", null, null, "local:album:old"),
        ))
        val fresh = canonicalPublicReleaseRows(
            listOf(recent, another), "2026-10-01", "2026-11-05",
        )
        val online = canonicalHomeWithWorldwideEditorial(
            listOf(stale), fresh, "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-05",
        )
        assertEquals(listOf("Lançamentos", "Escolha a dedo"), online.map { it.title })
        assertEquals(listOf(recent.musicBrainzId, another.musicBrainzId),
            online.first().items.map { it.browseId!!.removePrefix("catalog:RELEASE_GROUP:") })
        assertTrue(online.first().items.none { it.videoId != null })
        val offline = canonicalHomeWithWorldwideEditorial(
            listOf(stale), emptyList(), "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-05",
        )
        assertEquals(listOf(stale), offline)
    }

    @Test fun exploreGenreCardsAppearImmediatelyAfterPublicReleaseShelf() {
        val releases = HomeShelf("Lançamentos", listOf(
            ShelfItem("Verified", "Artist", null, null,
                "catalog:RELEASE_GROUP:${recent.musicBrainzId}"),
        ))
        val community = HomeShelf("Popular na comunidade", listOf(
            ShelfItem("Song", "Artist", null, null,
                "catalog:RECORDING:11111111-1111-4111-8111-111111111111"),
        ))
        val personal = HomeShelf("Para você", listOf(
            ShelfItem("Discovery", "Artist", null, null,
                "catalog:RECORDING:22222222-2222-4222-8222-222222222222"),
        ))
        val genres = canonicalExploreGenreShelf("Gêneros")
        val result = canonicalExploreDiscoveryShelves(listOf(releases, community), "Lançamentos", genres, personal)
        assertEquals(listOf("Lançamentos", "Gêneros", "Popular na comunidade", "Para você"),
            result.map { it.title })
        assertEquals(listOf(genres),
            canonicalExploreDiscoveryShelves(emptyList(), "Lançamentos", genres, null))
        // A daily public pick can contain a ReleaseGroup, but its shelf is
        // not Lançamentos. Categories should still lead Explore here.
        val picks = releases.copy(title = "Escolha a dedo")
        assertEquals(listOf("Gêneros", "Escolha a dedo"),
            canonicalExploreDiscoveryShelves(
                listOf(picks), "Lançamentos", genres, null,
            ).map { it.title })

    }

    @Test fun genreNavigationExistsWithoutAlbumsAndNeverProducesPlayableSongs() {
        val shelf = canonicalExploreGenreShelf("Gêneros")
        assertEquals("Gêneros", shelf.title)
        assertEquals(canonicalExploreGenres.size, shelf.items.size)
        assertTrue(shelf.items.all { it.videoId == null && it.thumbnailUrl == null })
        // The category card itself names the genre; repeating a metadata
        // provider below all twelve tiles is not useful user-facing content.
        assertTrue(shelf.items.all { it.subtitle.isBlank() })
        assertEquals("K-pop", canonicalExploreGenreForBrowseId("catalog:GENRE:k-pop")?.title)
        assertNull(canonicalExploreGenreForBrowseId("catalog:GENRE:youtube"))
        val genre = canonicalExploreGenreForBrowseId("catalog:GENRE:pop")!!
        val rows = canonicalPublicReleaseRows(listOf(recent), "1900-01-01", "2026-11-05")
        val results = canonicalPublicGenreReleaseShelf(genre, rows)
        assertEquals("catalog:RELEASE_GROUP:${recent.musicBrainzId}",
            results!!.items.single().browseId)
        assertNull(results.items.single().videoId)
    }

    @Test fun nativeGenreDetailBuildsDistinctEditorialSectionsWithoutInventedCharts() {
        val sample = buildList {
            repeat(5) { index ->
                add(recent.copy(
                    musicBrainzId = "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
                    title = "Album ${index + 1}",
                    firstReleaseDate = if (index < 2) "2026-11-01" else "2025-05-10",
                ))
            }
            repeat(5) { index ->
                add(another.copy(
                    musicBrainzId = "00000000-0000-4000-8001-${(index + 1).toString().padStart(12, '0')}",
                    title = "Single ${index + 1}",
                    firstReleaseDate = if (index < 2) "2026-11-01" else "2025-05-10",
                ))
            }
        }
        val rows = canonicalPublicReleaseRows(sample, "2025-01-01", "2026-11-06")
        val shelves = canonicalGenreEditorialShelves(
            canonicalExploreGenres.first { it.tag == "rock" }, rows, "2026-11-06",
        )
        assertTrue(shelves.size >= 3)
        assertTrue(shelves.first().title.contains("Rock"))
        assertTrue(shelves.any { it.title.contains("Lançamentos") })
        val allCards = shelves.flatMap { it.items }
        assertEquals(allCards.size, allCards.map { it.browseId }.distinct().size)
        assertTrue(allCards.all { it.videoId == null &&
            it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") })
        assertTrue(shelves.none { it.title.contains("Top") || it.title.contains("Em alta") })
    }

    @Test fun publicChartOverridesLocalEmAltaOnlyWithVerifiedRanking() {
        val fallback = HomeShelf("Em alta", emptyList())
        val preserved = canonicalHomeWithWorldwideEditorial(
            listOf(fallback), emptyList(), "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-05",
        )
        assertEquals(fallback, preserved.single())
        assertTrue(canonicalDailyPublicPicks(emptyList(), "2026-11-05").isEmpty())
    }
}
