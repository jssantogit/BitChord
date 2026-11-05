package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.ui.canonicalDailyPublicPicks
import com.music.bitchord.ui.canonicalExploreGenreForBrowseId
import com.music.bitchord.ui.canonicalExploreGenreShelf
import com.music.bitchord.ui.canonicalExploreGenres
import com.music.bitchord.ui.canonicalHomeWithWorldwideEditorial
import com.music.bitchord.ui.canonicalPublicReleaseRows
import com.music.bitchord.ui.canonicalPublicGenreReleaseShelf
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
            emptyList(), rows, "Escolha a dedo", "Em alta",
            null, "2026-11-05",
        )
        assertEquals("Escolha a dedo", home.single().title)
        assertFalse(home.single().items.isEmpty())
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

    @Test fun genreNavigationExistsWithoutAlbumsAndNeverProducesPlayableSongs() {
        val shelf = canonicalExploreGenreShelf("Gêneros")
        assertEquals("Gêneros", shelf.title)
        assertEquals(canonicalExploreGenres.size, shelf.items.size)
        assertTrue(shelf.items.all { it.videoId == null && it.thumbnailUrl == null })
        assertEquals("K-pop", canonicalExploreGenreForBrowseId("catalog:GENRE:k-pop")?.title)
        assertNull(canonicalExploreGenreForBrowseId("catalog:GENRE:youtube"))
        val genre = canonicalExploreGenreForBrowseId("catalog:GENRE:pop")!!
        val rows = canonicalPublicReleaseRows(listOf(recent), "1900-01-01", "2026-11-05")
        val results = canonicalPublicGenreReleaseShelf(genre, rows)
        assertEquals("catalog:RELEASE_GROUP:${recent.musicBrainzId}",
            results!!.items.single().browseId)
        assertNull(results.items.single().videoId)
    }

    @Test fun publicChartOverridesLocalEmAltaOnlyWithVerifiedRanking() {
        val fallback = HomeShelf("Em alta", emptyList())
        val preserved = canonicalHomeWithWorldwideEditorial(
            listOf(fallback), emptyList(), "Escolha a dedo", "Em alta", null, "2026-11-05",
        )
        assertEquals(fallback, preserved.single())
        assertTrue(canonicalDailyPublicPicks(emptyList(), "2026-11-05").isEmpty())
    }
}
