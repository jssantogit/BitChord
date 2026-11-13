package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialCatalog
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.catalog.metadata.ItunesVerifiedReleaseGroupLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesEditorialCatalogTest {
    private fun album(
        id: Long,
        title: String,
        storefront: String = "br",
        date: String = "2024-06-01",
        artistId: Long = 99,
        cover: String? = null,
    ) = ItunesCatalogItem(
        type = ItunesCatalogItemType.ALBUM,
        id = id,
        storefront = storefront,
        title = title,
        artistName = "Example Artist",
        artistId = artistId,
        collectionId = id,
        storeUrl = null,
        releaseDate = date,
        genre = "Pop",
        durationMs = null,
        trackNumber = null,
        discNumber = null,
        artworkUrl = cover,
    )

    private fun track(id: Long, collection: Long, number: Int) = ItunesCatalogItem(
        type = ItunesCatalogItemType.TRACK,
        id = id,
        storefront = "br",
        title = "Track $id",
        artistName = "Example Artist",
        artistId = 99,
        collectionId = collection,
        storeUrl = null,
        releaseDate = null,
        genre = null,
        durationMs = 120_000,
        trackNumber = number,
        discNumber = 1,
    )

    @Test fun appleOwnsReleaseExistenceTitlesAndDisplayedDiscographyEvenWithoutMusicBrainz() {
        val apple = listOf(
            album(1, "HOT - EP", date = "2024-05-01"),
            album(2, "HOT - Single", date = "2024-06-01"),
            album(3, "Another Version", storefront = "us"),
        )
        val result = ItunesEditorialCatalog.assemble("br", apple)
        assertEquals(listOf("HOT - Single", "HOT - EP"), result.map { it.title })
        assertEquals(listOf(2L, 1L), result.map { it.appleCollectionId })
        assertEquals(listOf(
            ItunesEditorialReleaseFormat.SINGLE,
            ItunesEditorialReleaseFormat.EP,
        ), result.map { it.format })
        assertTrue(result.all { it.musicBrainzReleaseGroupId == null })
    }

    @Test fun appleRowsDoNotMergeSameTitleEditionsOrInheritMusicBrainzType() {
        val apple = listOf(
            album(10, "EASY", date = "2024-04-01"),
            album(11, "EASY", date = "2024-03-01"),
            album(10, "EASY", date = "2024-04-01"),
        )
        val linked = listOf(ItunesVerifiedReleaseGroupLink(
            appleScopedId = "itunes:br:album:10",
            musicBrainzReleaseGroupId = "f2eaa98b-fef5-4327-9e7f-7d6500fdb973",
        ))
        val result = ItunesEditorialCatalog.assemble("br", apple, linked)
        assertEquals(2, result.size)
        assertEquals(listOf(10L, 11L), result.map { it.appleCollectionId })
        assertEquals(ItunesEditorialReleaseFormat.UNCLASSIFIED, result[0].format)
        assertEquals("f2eaa98b-fef5-4327-9e7f-7d6500fdb973",
            result[0].musicBrainzReleaseGroupId)
        assertNull(result[1].musicBrainzReleaseGroupId)
    }

    @Test fun appleTracklistIsEditionScopedAndKeepsDiscTrackOrdering() {
        val items = listOf(
            album(10, "My Release - EP"),
            track(102, 10, 2),
            track(200, 20, 1),
            track(101, 10, 1),
        )
        val result = ItunesEditorialCatalog.assemble("br", items)
        assertEquals(listOf(101L, 102L), result.single().tracks.map { it.id })
    }

    @Test fun invalidOrForeignIdentityLinksNeverBecomeCanonical() {
        val item = album(10, "Release")
        val result = ItunesEditorialCatalog.assemble(
            "br",
            listOf(item),
            listOf(
                ItunesVerifiedReleaseGroupLink("itunes:us:album:10",
                    "f2eaa98b-fef5-4327-9e7f-7d6500fdb973"),
                ItunesVerifiedReleaseGroupLink("itunes:br:album:10", "fake"),
            )
        )
        assertNull(result.single().musicBrainzReleaseGroupId)
    }
}
