package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleNativeHomeDiscoveryTest {
    private fun release(
        id: Long, tag: String, storefront: String = "br",
        date: String? = "2026-11-12",
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:$storefront:album:$id",
        appleCollectionId = id,
        storefront = storefront,
        title = "Release $id",
        artistName = "Artist $id",
        artistId = id + 1,
        releaseDate = date,
        genre = when (tag) {
            "hip-hop" -> "Hip-Hop/Rap"
            "rnb" -> "R&B/Soul"
            "k-pop" -> "K-Pop"
            else -> tag.replaceFirstChar { it.uppercase() }
        },
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = "https://is1-ssl.mzstatic.com/a.jpg",
        trackCount = 1,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    @Test fun nativeHomeDiscoversAppleProductsWithoutMbidsOrMandatoryMusicBrainz() =
        runBlocking {
            val tags = AppleNativeHomeDiscovery.sampleTags(LocalDate.parse("2026-11-17"))
            val queried = mutableListOf<String>()
            val updates = mutableListOf<List<String>>()
            val home = AppleNativeHomeDiscovery(
                source = AppleNativeHomeGenreSource { tag, storefront ->
                    queried += "$tag:$storefront"
                    AppleEditorialLoad.Success(listOf(
                        release(100 + queried.size.toLong(), tag).copy(
                            musicBrainzReleaseGroupId =
                                "529f87a1-1081-48a4-8ee1-3c08de229858",
                        ),
                        release(200, tag, storefront = "us"),
                        release(300, "rock").copy(releaseDate = "2026-11-18"),
                    ), AppleEditorialReadState.COMPLETE)
                },
            )
            val result = home.discover(
                "br", "2026-11-17", onProgress = { releases ->
                    updates += releases.map { it.appleScopedId }
                },
            ) as AppleEditorialLoad.Success
            assertEquals(tags.map { "$it:br" }, queried)
            assertEquals(3, result.releases.size)
            assertTrue(result.releases.all { it.appleScopedId.startsWith("itunes:br:album:") })
            assertTrue(result.releases.all { it.musicBrainzReleaseGroupId == null })
            assertEquals(listOf(1, 2, 3), updates.map { it.size })
            assertTrue(result.releases.all { it.releaseDate == "2026-11-12" })
            assertEquals(3, result.probeReport?.probesMade)
        }

    @Test fun conflictingAppleProductClaimsAreRemovedRatherThanArbitrarilyMerged() =
        runBlocking {
            val today = LocalDate.parse("2026-11-17")
            val tags = AppleNativeHomeDiscovery.sampleTags(today)
            val home = AppleNativeHomeDiscovery(
                source = AppleNativeHomeGenreSource { tag, _ ->
                    AppleEditorialLoad.Success(listOf(
                        release(99, tag).copy(
                            title = if (tag == tags.first()) "First" else "Contradiction",
                        ),
                    ), AppleEditorialReadState.COMPLETE)
                },
            )
            val result = home.discover("br", today.toString()) as AppleEditorialLoad.Success
            assertTrue(result.releases.isEmpty())
            assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        }

    @Test fun failureOfOneGenreDoesNotTurnOtherGenresIntoHiddenMbFallback() = runBlocking {
        val tags = AppleNativeHomeDiscovery.sampleTags(LocalDate.parse("2026-11-17"))
        val home = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { tag, _ ->
                if (tag == tags.first()) AppleEditorialLoad.Failure(
                    ItunesCatalogFailure.UNAVAILABLE,
                ) else AppleEditorialLoad.Success(
                    listOf(release(42 + tags.indexOf(tag).toLong(), tag)),
                    AppleEditorialReadState.COMPLETE,
                )
            },
        )
        val result = home.discover("br", "2026-11-17") as AppleEditorialLoad.Success
        assertEquals(2, result.releases.size)
        assertEquals(1, result.probeReport?.failedRequests)
        assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
    }

    @Test fun malformedDateAndStorefrontNeverIssueAppleRequest() = runBlocking {
        var probed = false
        val home = AppleNativeHomeDiscovery(
            source = AppleNativeHomeGenreSource { _, _ ->
                probed = true
                AppleEditorialLoad.Success(emptyList(), AppleEditorialReadState.COMPLETE)
            },
        )
        assertTrue(home.discover("invalid", "2026-11-17") is AppleEditorialLoad.Failure)
        assertTrue(home.discover("br", "not-a-date") is AppleEditorialLoad.Failure)
        assertTrue(home.discover("br", "2026-11-17", maxGenres = 10) is AppleEditorialLoad.Failure)
        assertFalse(probed)
    }
}
