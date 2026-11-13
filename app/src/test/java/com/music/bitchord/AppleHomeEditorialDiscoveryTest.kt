package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleHomeEditorialDiscoveryTest {
    private fun album(id: Long, title: String, artist: String = "ILLIT",
        storefront: String = "br", date: String = "2026-11-01",
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:$storefront:album:$id",
        appleCollectionId = id, storefront = storefront,
        title = title, artistName = artist, artistId = 999L,
        releaseDate = date, genre = "K-Pop",
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = "https://is1-ssl.mzstatic.com/a.jpg",
        trackCount = 1, tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
    )
    private fun found(vararg rows: ItunesEditorialRelease) = AppleEditorialLoad.Success(
        rows.toList(), AppleEditorialReadState.COMPLETE,
    )

    @Test fun onlyAppleConfirmedExactTitlesAndArtistsBecomeHomeProducts() = runBlocking {
        val asked = mutableListOf<String>()
        val collector = AppleHomeEditorialDiscovery(
            source = AppleHomeAlbumSearchSource { title, storefront ->
                asked += "$title:$storefront"
                found(
                    album(10, "SUPER REAL ME - EP"),
                    album(11, "SUPER REAL ME", artist = "Another artist"),
                    album(12, "Different album"),
                    album(13, "SUPER REAL ME", storefront = "us"),
                )
            },
        )
        val result = collector.discover(
            seeds = listOf(AppleHomeEditorialSeed("SUPER REAL ME", "ILLIT")),
            storefront = "br",
        ) as AppleEditorialLoad.Success
        assertEquals(listOf("SUPER REAL ME:br"), asked)
        assertEquals(listOf("itunes:br:album:10"),
            result.releases.map { it.appleScopedId })
        assertEquals("SUPER REAL ME - EP", result.releases.single().title)
        assertNull(result.releases.single().musicBrainzReleaseGroupId)
        assertNull(result.releases.single().musicBrainzArtistId)
    }

    @Test fun duplicateAppleProductWithConflictingFieldsIsNeverChosenBySearchOrder() =
        runBlocking {
            val collector = AppleHomeEditorialDiscovery(
                source = AppleHomeAlbumSearchSource { _, _ ->
                    found(album(10, "Shared title").copy(upc = "8801234567890"),
                        album(10, "Shared title").copy(upc = "8802222222222"))
                },
            )
            // Two separate candidate probes can return the same album identity.
            // Even when both are Apple responses, differing edition metadata
            // must be discarded rather than accepted as first-wins.
            val result = collector.discover(
                listOf(AppleHomeEditorialSeed("Shared title", "ILLIT")),
                "br",
            ) as AppleEditorialLoad.Success
            assertTrue(result.releases.isEmpty())
            assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        }

    @Test fun noMbidsOrPlaybackCanEnterThroughCandidateSources() = runBlocking {
        val mbid = "529f87a1-1081-48a4-8ee1-3c08de229858"
        val collector = AppleHomeEditorialDiscovery(
            source = AppleHomeAlbumSearchSource { _, _ ->
                found(album(1, "Title").copy(
                    musicBrainzReleaseGroupId = mbid,
                    musicBrainzArtistId = mbid,
                ))
            },
        )
        val result = collector.discover(
            listOf(AppleHomeEditorialSeed("Title", "ILLIT")), "br",
        ) as AppleEditorialLoad.Success
        assertNull(result.releases.single().musicBrainzReleaseGroupId)
        assertNull(result.releases.single().musicBrainzArtistId)
    }

    @Test fun boundedIndependentSearchDoesNotClaimExhaustiveResultsWhenOneFails() =
        runBlocking {
            val asked = mutableListOf<String>()
            val collector = AppleHomeEditorialDiscovery(
                source = AppleHomeAlbumSearchSource { title, _ ->
                    asked += title
                    if (title == "First") AppleEditorialLoad.Failure(
                        ItunesCatalogFailure.UNAVAILABLE,
                    ) else found(album(3, "Second"))
                },
            )
            val result = collector.discover(
                (listOf(AppleHomeEditorialSeed("First", "ILLIT"),
                    AppleHomeEditorialSeed("Second", "ILLIT")) +
                    (1..20).map { AppleHomeEditorialSeed("Extra $it", "ILLIT") }),
                "br",
                maxProbes = 2,
            ) as AppleEditorialLoad.Success
            assertEquals(listOf("First", "Second"), asked)
            assertEquals(listOf(3L), result.releases.map { it.appleCollectionId })
            assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        }
}
