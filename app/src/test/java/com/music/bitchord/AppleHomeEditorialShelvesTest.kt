package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.appleFirstHomeShelves
import org.junit.Assert.*
import org.junit.Test

class AppleHomeEditorialShelvesTest {
    private fun album(id: Long, title: String, date: String? = "2026-11-05") =
        ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:$id", appleCollectionId = id,
            storefront = "br", title = title, artistName = "ILLIT",
            artistId = 42, releaseDate = date, genre = "K-pop",
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
            artworkUrl = "https://is1-ssl.mzstatic.com/cover.jpg",
            trackCount = 5, tracks = emptyList(),
            musicBrainzReleaseGroupId = null,
            musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
        )

    @Test fun homeUsesOnlyAppleProductsForPublicReleaseAndDiscoveryShelves() {
        val old = HomeShelf("Lançamentos", listOf(ShelfItem(
            "MusicBrainz Only", "Someone", "https://mb.org/cover.jpg",
            null, "catalog:RELEASE_GROUP:529f87a1-1081-48a4-8ee1-3c08de229858",
        )))
        val local = HomeShelf("Músicas para você", listOf(ShelfItem(
            "Local Song", "My library", null, null, "local:pick:123",
        )))
        val shelves = appleFirstHomeShelves(
            appleAlbums = listOf(album(10, "SUPER REAL ME"),
                album(11, "Older catalog item", date = "2021-01-01")),
            existingLocalShelves = listOf(old, local),
            releasesHeading = "Lançamentos",
            discoveryHeading = "Descoberta global",
            todayIsoDate = "2026-11-13",
        )
        assertEquals(listOf("itunes:br:album:10"),
            shelves.first { it.title == "Lançamentos" }.items.map { it.browseId })
        assertEquals(listOf("itunes:br:album:11"),
            shelves.first { it.title == "Descoberta global" }.items.map { it.browseId })
        assertTrue(shelves.flatMap { it.items }.none {
            it.browseId.orEmpty().startsWith("catalog:")
        })
        assertTrue(shelves.flatMap { it.items }.any { it.browseId == "local:pick:123" })
        assertTrue(shelves.flatMap { it.items }.filter {
            it.browseId.orEmpty().startsWith("itunes:")
        }.all { it.videoId == null })
    }

    @Test fun futureAndInvalidDatesCannotBeCalledNewReleases() {
        val shelves = appleFirstHomeShelves(
            appleAlbums = listOf(album(10, "Future", date = "2027-01-01"),
                album(11, "Undated", date = null),
                album(12, "Old", date = "2020-02-01")),
            existingLocalShelves = emptyList(),
            releasesHeading = "Lançamentos",
            discoveryHeading = "Descoberta",
            todayIsoDate = "2026-11-13",
        )
        assertTrue(shelves.none { it.title == "Lançamentos" })
        assertTrue(shelves.flatMap { it.items }.none { it.title == "Future" })
        assertEquals(setOf("Undated", "Old"),
            shelves.flatMap { it.items }.map { it.title }.toSet())
    }

    @Test fun duplicateOrContradictoryAppleIDsCannotGetTwoHomeCards() {
        val result = appleFirstHomeShelves(
            appleAlbums = listOf(
                album(10, "A"), album(10, "A"),
                album(20, "B"), album(20, "Contradiction"),
            ),
            existingLocalShelves = emptyList(),
            releasesHeading = "Recentes", discoveryHeading = "Descoberta",
            todayIsoDate = "2026-11-13",
        )
        assertEquals(listOf("itunes:br:album:10"),
            result.flatMap { it.items }.map { it.browseId })
    }
}
