package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.model.HomeShelf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleExploreGenreEditorialTest {
    private fun edition(id: Long, genre: String?) = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id, storefront = "br",
        title = "Music $id", artistName = "Band", artistId = 42,
        releaseDate = "2026-11-01", genre = genre,
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = "https://is1-ssl.mzstatic.com/music.jpg",
        trackCount = 2, tracks = emptyList(),
        musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    @Test fun kpopMusicBrainzTagsAloneDoNotMakeAnApplePopAlbumIntoKpop() {
        val shelves = com.music.bitchord.ui.appleFirstGenreShelves(
            tag = "k-pop",
            appleAlbums = listOf(edition(101, "Pop"), edition(102, "K-Pop")),
            publicPlaylists = null,
            releaseHeading = "Lançamentos",
        )
        assertEquals(listOf("itunes:br:album:102"),
            shelves.flatMap { it.items }.map { it.browseId })
    }

    @Test fun genrePresentationPreservesStorefrontAndNativeAppleAlbumRouting() {
        val shelves = com.music.bitchord.ui.appleFirstGenreShelves(
            tag = "hip-hop",
            appleAlbums = listOf(edition(5, "Hip-Hop/Rap"),
                edition(6, "Rock"), edition(7, null)),
            publicPlaylists = HomeShelf("Playlists", emptyList()),
            releaseHeading = "Lançamentos",
        )
        assertEquals(listOf("itunes:br:album:5"),
            shelves.flatMap { it.items }.map { it.browseId })
        assertTrue(shelves.flatMap { it.items }.all { it.videoId == null })
        assertTrue(shelves.none { it.title == "Playlists" })
    }

    @Test fun boundedAppleGenreDiscoveryOnlyUsesAppleCategoryAndNeverCopiesMbids() =
        runBlocking {
            val calls = mutableListOf<String>()
            val collector = AppleExploreGenreDiscovery(
                catalog = AppleHomeEditorialDiscovery(
                    source = AppleHomeAlbumSearchSource { title, _ ->
                        calls += title
                        val id = if (title == "Music 5") 5 else 6
                        AppleEditorialLoad.Success(
                            listOf(edition(id.toLong(),
                                if (id == 5) "K-Pop" else "Pop").copy(
                                musicBrainzReleaseGroupId =
                                    "529f87a1-1081-48a4-8ee1-3c08de229858",
                            )),
                            AppleEditorialReadState.COMPLETE,
                        )
                    },
                ),
            )
            val result = collector.discover(
                tag = "k-pop",
                seeds = listOf(
                    AppleHomeEditorialSeed("Music 5", "Band"),
                    AppleHomeEditorialSeed("Music 6", "Band"),
                ),
                storefront = "br",
            ) as AppleEditorialLoad.Success
            assertEquals(listOf("Music 5", "Music 6"), calls)
            assertEquals(listOf("itunes:br:album:5"),
                result.releases.map { it.appleScopedId })
            assertNull(result.releases.single().musicBrainzReleaseGroupId)
        }

    @Test fun exploreCategoryCoversComeFromAppleGenreProductsNotMbSuggestions() {
        val result = com.music.bitchord.ui.appleExploreGenreCoverMap(
            appleAlbums = listOf(edition(1, "K-Pop"), edition(2, "Pop")),
            tags = listOf("k-pop", "pop", "metal"),
        )
        assertEquals("https://is1-ssl.mzstatic.com/music.jpg", result["k-pop"])
        assertEquals("https://is1-ssl.mzstatic.com/music.jpg", result["pop"])
        assertNull(result["metal"])
        assertEquals(2, result.size)
    }

    @Test fun unsupportedTagFailsClosedWithoutAnyAppleQuery() = runBlocking {
        var called = false
        val collector = AppleExploreGenreDiscovery(
            catalog = AppleHomeEditorialDiscovery(
                source = AppleHomeAlbumSearchSource { _, _ ->
                    called = true
                    AppleEditorialLoad.Success(emptyList(),
                        AppleEditorialReadState.NOT_IN_STOREFRONT)
                },
            ),
        )
        assertTrue(collector.discover(
            "unverified-tag", listOf(AppleHomeEditorialSeed("Music 5", "Band")), "br",
        ) is AppleEditorialLoad.Failure)
        assertFalse(called)
    }
}
