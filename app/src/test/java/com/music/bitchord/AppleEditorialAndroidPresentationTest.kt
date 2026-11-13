package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialClassification
import com.music.bitchord.data.catalog.metadata.AppleMusicBrainzEditionMatch
import com.music.bitchord.data.catalog.metadata.AppleTriSourceEditorialEntry
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.AppleCatalogBrowseRoute
import com.music.bitchord.ui.AppleEditorialShelfHeadings
import com.music.bitchord.ui.appleEditorialShelves
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialAndroidPresentationTest {
    private fun release(
        id: Long,
        title: String,
        type: ItunesEditorialReleaseFormat,
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id,
        storefront = "br",
        title = title,
        artistName = "ILLIT",
        artistId = 99,
        releaseDate = "2024-03-25",
        genre = "K-Pop",
        format = type,
        artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/cover.jpg",
        trackCount = 1,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    private fun entry(release: ItunesEditorialRelease) = AppleTriSourceEditorialEntry(
        release = release,
        match = AppleMusicBrainzEditionMatch.NoMatch,
        classification = AppleEditorialClassification.resolve(release, emptyList()),
        discogsEdition = null,
    )

    @Test fun typedItunesRoutesNeverMasqueradeAsMusicBrainzBrowseIds() {
        val route = AppleCatalogBrowseRoute.parse("itunes:br:album:123")
        assertNotNull(route)
        assertEquals("br", route?.storefront)
        assertEquals(123L, route?.id)
        assertEquals(ItunesCatalogItemType.ALBUM, route?.kind)
        assertNull(AppleCatalogBrowseRoute.parse("catalog:RELEASE_GROUP:123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:0"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:00123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:BR:album:123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:123:extra"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:-123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:release-group:123"))
        assertNull(AppleCatalogBrowseRoute.parse("itunes:br:album:999999999999999999999999"))
    }

    @Test fun albumAndSingleWithSameNameKeepDifferentAppleProductRoutesAndNoPlayability() {
        val labels = AppleEditorialShelfHeadings(
            albums = "Álbuns", eps = "EPs", singles = "Singles",
            unclassified = "Lançamentos",
        )
        val shelves = appleEditorialShelves(
            listOf(
                entry(release(101, "HOT", ItunesEditorialReleaseFormat.EP)),
                entry(release(102, "HOT", ItunesEditorialReleaseFormat.SINGLE)),
                entry(release(103, "HOT", ItunesEditorialReleaseFormat.ALBUM)),
                entry(release(104, "Unknown", ItunesEditorialReleaseFormat.UNCLASSIFIED)),
            ),
            headings = labels,
        )
        assertEquals(listOf("Álbuns", "EPs", "Singles", "Lançamentos"),
            shelves.map { it.title })
        val cards = shelves.flatMap { it.items }
        assertEquals(4, cards.size)
        assertEquals(setOf(
            "itunes:br:album:101", "itunes:br:album:102",
            "itunes:br:album:103", "itunes:br:album:104",
        ), cards.mapNotNull { it.browseId }.toSet())
        assertTrue(cards.all { it.videoId == null })
        assertTrue(cards.all { it.subtitle.contains("ILLIT") })
        assertEquals(4, cards.mapNotNull { it.browseId }.distinct().size)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/cover.jpg",
            cards.first().thumbnailUrl)
    }

    @Test fun shelvesRemainLimitedAndDoNotAssumeUnknownIsAnAlbum() {
        val many = (1L..30L).map { id ->
            entry(release(id, "Release $id", ItunesEditorialReleaseFormat.UNCLASSIFIED))
        }
        val shelves = appleEditorialShelves(
            many,
            headings = AppleEditorialShelfHeadings(
                albums = "Albums", eps = "EPs", singles = "Singles",
                unclassified = "Releases",
            ),
            maxPerShelf = 12,
        )
        assertEquals(1, shelves.size)
        assertEquals("Releases", shelves.single().title)
        assertEquals(12, shelves.single().items.size)
        assertTrue(shelves.single().items.all { it.videoId == null })
    }
}
