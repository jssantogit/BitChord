package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.ui.canonicalPublicPlaylistShelf
import com.music.bitchord.ui.canonicalGenreDisplayState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.canonicalExploreDiscoveryShelves
import com.music.bitchord.ui.canonicalDailyPublicPicks
import com.music.bitchord.ui.canonicalExploreGenreForBrowseId
import com.music.bitchord.ui.canonicalExploreGenreShelf
import com.music.bitchord.ui.canonicalExploreGenres
import com.music.bitchord.ui.canonicalHomeWithWorldwideEditorial
import com.music.bitchord.ui.canonicalPublicReleaseRows
import com.music.bitchord.ui.canonicalPublicGenreRows
import com.music.bitchord.ui.canonicalPublicNeedsMoreEditorialRows
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
        assertEquals(listOf(recent.musicBrainzId),
            online.first().items.map { it.browseId!!.removePrefix("catalog:RELEASE_GROUP:") })
        assertEquals("catalog:RELEASE_GROUP:${another.musicBrainzId}",
            online.first { it.title == "Escolha a dedo" }.items.single().browseId)
        assertEquals(online.flatMap { it.items }.size,
            online.flatMap { it.items }.map { it.browseId }.distinct().size)
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

    @Test fun genrePagesIncludeHistoricallyVerifiedAlbumsWithPartialOrUnknownDates() {
        val samples = listOf(
            recent.copy(musicBrainzId = "11111111-1111-4111-8111-111111111111",
                title = "Year only", firstReleaseDate = "1998"),
            recent.copy(musicBrainzId = "22222222-2222-4222-8222-222222222222",
                title = "Month only", firstReleaseDate = "2001-04"),
            recent.copy(musicBrainzId = "33333333-3333-4333-8333-333333333333",
                title = "Undated album", firstReleaseDate = null),
            recent.copy(musicBrainzId = "44444444-4444-4444-8444-444444444444",
                title = "Future year", firstReleaseDate = "2028"),
            recent.copy(musicBrainzId = "55555555-5555-4555-8555-555555555555",
                title = "Invalid month", firstReleaseDate = "2001-14"),
            recent.copy(musicBrainzId = "66666666-6666-4666-8666-666666666666",
                title = "Uncredited", artistDisplay = null, firstReleaseDate = "1998"),
            recent.copy(musicBrainzId = "77777777-7777-4777-8777-777777777777",
                title = "Invalid type", primaryType = "Other", firstReleaseDate = "1998"),
        )
        // The worldwide new-releases shelf must remain strict; genre discovery
        // is not claiming that an undated or year-only album launched recently.
        assertTrue(canonicalPublicReleaseRows(samples, "2026-10-01", "2026-11-06").isEmpty())
        val rows = canonicalPublicGenreRows(samples, "2026-11-06")
        assertEquals(listOf("Month only", "Year only", "Undated album"), rows.map { it.title })
        assertEquals(listOf("2001-04", "1998", null), rows.map { it.releaseDate })
        val shelves = canonicalGenreEditorialShelves(
            canonicalExploreGenres.first { it.tag == "rock" }, rows, "2026-11-06",
        )
        assertTrue(shelves.isNotEmpty())
        assertTrue(shelves.none { it.title.startsWith("Lançamentos") })
        assertTrue(shelves.flatMap { it.items }.all {
            it.videoId == null && it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:")
        })
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
        assertEquals("Para descobrir", shelves.first().title)
        assertTrue(shelves.any { it.title == "Lançamentos" })
        assertTrue(shelves.any { it.title == "Álbuns" })
        assertTrue(shelves.any { it.title == "Singles e EPs" })
        assertTrue(shelves.none { it.title.contains(" · Rock") })
        val allCards = shelves.flatMap { it.items }
        assertEquals(allCards.size, allCards.map { it.browseId }.distinct().size)
        assertTrue(allCards.all { it.videoId == null &&
            it.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") })
        assertTrue(shelves.none { it.title.contains("Top") || it.title.contains("Em alta") })
    }

    @Test fun genreCategoryCardsCanUseVerifiedCoversWithoutChangingNavigation() {
        val covers = mapOf("rock" to "https://coverartarchive.org/release-group/verified/front-500")
        val rows = canonicalExploreGenreShelf("Gêneros", covers).items
        assertEquals(covers["rock"], rows.first { it.title == "Rock" }.thumbnailUrl)
        assertNull(rows.first { it.title == "Pop" }.thumbnailUrl)
        assertEquals("catalog:GENRE:rock", rows.first { it.title == "Rock" }.browseId)
    }

    @Test fun sparseGenreStillReservesRealAlbumAndSingleSections() {
        val samples = listOf(
            recent.copy(musicBrainzId = "10000000-0000-4000-8000-000000000001", title = "A1"),
            recent.copy(musicBrainzId = "10000000-0000-4000-8000-000000000002", title = "A2"),
            recent.copy(musicBrainzId = "10000000-0000-4000-8000-000000000003", title = "A3"),
            another.copy(musicBrainzId = "10000000-0000-4000-8000-000000000004", title = "S1"),
            another.copy(musicBrainzId = "10000000-0000-4000-8000-000000000005", title = "S2"),
            another.copy(musicBrainzId = "10000000-0000-4000-8000-000000000006", title = "S3"),
        )
        val rows = canonicalPublicReleaseRows(samples, "2026-10-01", "2026-11-06")
        val shelves = canonicalGenreEditorialShelves(
            canonicalExploreGenres.first { it.tag == "rock" }, rows, "2026-11-06",
        )
        assertTrue(shelves.any { it.title == "Lançamentos" })
        assertTrue(shelves.any { it.title == "Álbuns" })
        assertTrue(shelves.any { it.title == "Singles e EPs" })
        assertEquals(shelves.flatMap { it.items }.size,
            shelves.flatMap { it.items }.map { it.browseId }.distinct().size)
    }

    @Test fun tinyPublicBatchRequiresMoreDiscoveryBeforeHomeIsComplete() {
        val few = canonicalPublicReleaseRows(listOf(recent, another), "2026-10-01", "2026-11-06")
        assertTrue(canonicalPublicNeedsMoreEditorialRows(few))
        val many = (1..36).map { index ->
            val hit = if (index % 2 == 0) recent else another
            hit.copy(musicBrainzId = "30000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                title = "Release ${index}")
        }
        val rows = canonicalPublicReleaseRows(many, "2026-10-01", "2026-11-06")
        assertFalse(canonicalPublicNeedsMoreEditorialRows(rows))
    }

    @Test fun publicPlaylistsMakeGenreAndHomeDifferentWithoutFakeMedia() {
        val playlist = ListenBrainzPublicPlaylist(
            "33333333-3333-4333-8333-333333333333",
            "Pop Discovery", "Curator", "Pop genre collection",
        )
        val playlists = canonicalPublicPlaylistShelf("Playlists", listOf(playlist))!!
        assertEquals(playlist.browseId, playlists.items.single().browseId)
        assertTrue(playlists.items.single().videoId == null)

        val rows = canonicalPublicReleaseRows(
            (1..10).map { index ->
                val hit = if (index % 2 == 0) recent else another
                hit.copy(
                    musicBrainzId = "40000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                    title = "Release ${index}",
                )
            }, "2026-10-01", "2026-11-06",
        )
        val genre = canonicalGenreEditorialShelves(
            canonicalExploreGenres.first { it.tag == "pop" }, rows,
            "2026-11-06", playlists = listOf(playlist),
        )
        assertEquals("Para descobrir", genre.first().title)
        assertEquals(1, genre.count { it.title == "Playlists" })
        // A public playlist stays discoverable without a MusicBrainz release
        // batch; an upstream outage must not erase the independent result.
        assertEquals(listOf("Playlists"), canonicalGenreEditorialShelves(
            canonicalExploreGenres.first { it.tag == "pop" }, emptyList(),
            "2026-11-06", playlists = listOf(playlist),
        ).map { it.title })
        val offline = canonicalGenreDisplayState(
            canonicalExploreGenres.first { it.tag == "pop" },
            UiState.Error("MusicBrainz unavailable"),
            listOf(playlist), "2026-11-06",
        )
        assertTrue(offline is UiState.Success)
        assertEquals("Playlists", (offline as UiState.Success).data.single().title)
        assertTrue(genre.any { it.title == "Singles e EPs" })
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), rows, "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-06",
            publicPlaylists = playlists.copy(title = "Playlists para descobrir"),
        )
        assertTrue(home.any { it.title == "Playlists para descobrir" })
        assertTrue(home.none { it.title == "Singles e EPs" })
        assertTrue(home.flatMap { it.items }.all { it.videoId == null })
    }

    @Test fun worldwideHomeUsesDistinctReleasesAndFourEditorialSectionsWhenAvailable() {
        val all = (1..40).map { index ->
            val id = "00000000-0000-4000-8002-${index.toString().padStart(12, '0')}"
            val type = if (index % 3 == 0) "Single" else "Album"
            recent.copy(
                musicBrainzId = id,
                title = "Verified title $index",
                primaryType = type,
                firstReleaseDate = "2026-11-01",
                // The CAA identity for each cover is supplied by metadata,
                // never inferred from a different title or provider.
                artworkUri = if (index % 2 == 0) "https://coverartarchive.org/release-group/$id/front-500" else null,
            )
        }
        val rows = canonicalPublicReleaseRows(all, "2026-10-01", "2026-11-06")
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), rows, "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-06", "Álbuns", "Singles e EPs", "Mais",
        )
        assertTrue("Expected multiple independent editorial sections", home.size >= 4)
        assertEquals("Lançamentos", home.first().title)
        val ids = home.flatMap { it.items }.map { it.browseId }
        assertEquals("The same album must not appear twice in Home", ids.size, ids.distinct().size)
        assertTrue(home.flatMap { it.items }.all { it.videoId == null })
        assertTrue(home.first { it.title == "Escolha a dedo" }.items.first().thumbnailUrl != null)
        // No worldwide ranking is fabricated from release dates alone.
        assertTrue(home.none { it.title == "Em alta" })
    }

    @Test fun aSmallVerifiedReleaseBatchCanStillFillFiveDistinctHomeShelves() {
        val samples = (1..8).map { index ->
            val hit = if (index % 2 == 0) recent else another
            hit.copy(
                musicBrainzId = "40000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                title = "Album or single ${index}",
            )
        }
        val rows = canonicalPublicReleaseRows(samples, "2026-10-01", "2026-11-06")
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), rows, "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-06", "Álbuns", "Singles e EPs", "Mais para explorar",
        )
        assertEquals(
            listOf("Lançamentos", "Escolha a dedo", "Álbuns",
                "Singles e EPs", "Mais para explorar"),
            home.map { it.title },
        )
        val cards = home.flatMap { it.items }
        assertEquals(8, cards.size)
        assertEquals(cards.size, cards.map { it.browseId }.distinct().size)
        assertTrue(home.none { it.title == "Em alta" })
    }

    @Test fun rankingShelfKeepsItsRealUpstreamEvidenceRatherThanInventingCharts() {
        val real = HomeShelf("Em alta", listOf(
            ShelfItem("Observed weekly song", "Artist", null, null,
                "catalog:RECORDING:11111111-1111-4111-8111-111111111111"),
        ))
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), emptyList(), "Lançamentos", "Escolha a dedo", "Em alta",
            real, "2026-11-06", "Álbuns", "Singles e EPs", "Mais",
        )
        assertEquals(real, home.single())
    }

    @Test fun homeSeparatesLaunchesPicksAndAdditionalAlbumAndSingleShelves() {
        val releases = (1..55).map { index ->
            val kind = when (index % 3) {
                0 -> "Album"
                1 -> "Single"
                else -> "EP"
            }
            recent.copy(
                musicBrainzId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                title = "Unique release ${index}",
                primaryType = kind,
                firstReleaseDate = "2026-11-${(1 + index % 5).toString().padStart(2, '0')}",
                artworkUri = "https://coverartarchive.org/release-group/${index}/front-500",
            )
        }
        val rows = canonicalPublicReleaseRows(releases, "2026-10-01", "2026-11-06")
        val chart = HomeShelf("Em alta", listOf(
            ShelfItem("World chart", "Global artist", null, null,
                "catalog:RECORDING:11111111-1111-4111-8111-111111111111"),
        ))
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), rows, "Lançamentos", "Escolha a dedo", "Em alta",
            chart, "2026-11-06", "Álbuns recentes", "Singles e EPs",
        )
        assertEquals(
            listOf("Lançamentos", "Escolha a dedo", "Em alta",
                "Álbuns recentes", "Singles e EPs"), home.map { it.title },
        )
        val cards = home.flatMap { it.items }
        assertEquals(cards.size, cards.map { it.browseId }.distinct().size)
        assertEquals(cards.size, cards.map { it.title to it.subtitle }.distinct().size)
        assertTrue(home.all { it.items.isNotEmpty() })
        assertTrue(cards.all { it.videoId == null })
    }

    @Test fun allTimeCommunityEvidenceIsLabeledHonestlyWhenWeeklyChartUnavailable() {
        val observed = HomeShelf("Popular na comunidade", listOf(
            ShelfItem("Observed recording", "Artist", null, null,
                "catalog:RECORDING:33333333-3333-4333-8333-333333333333"),
        ))
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), emptyList(), "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-06", community = observed,
        )
        assertEquals(listOf(observed), home)
        assertTrue(home.none { it.title == "Em alta" })
    }

    @Test fun verifiedPersonalRecommendationsBelongOnHomeNotGenreRoot() {
        val personal = HomeShelf("Para você", listOf(
            ShelfItem("Verified recording", "Credited artist", null, null,
                "catalog:RECORDING:44444444-4444-4444-8444-444444444444"),
        ))
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), emptyList(), "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-06", personal = personal,
        )
        assertEquals(listOf(personal), home)
    }

    @Test fun personalAndCommunityCardsDoNotShowTheSameRecordingTwice() {
        val item = ShelfItem("Shared recording", "Artist", null, null,
            "catalog:RECORDING:44444444-4444-4444-8444-444444444444")
        val home = canonicalHomeWithWorldwideEditorial(
            emptyList(), emptyList(), "Lançamentos", "Escolha a dedo", "Em alta",
            null, "2026-11-06",
            community = HomeShelf("Popular na comunidade", listOf(item)),
            personal = HomeShelf("Para você", listOf(item)),
        )
        assertEquals(1, home.flatMap { it.items }.count { it.browseId == item.browseId })
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
