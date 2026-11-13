package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialSeedPlannerTest {
    private fun seed(title: String, artist: String) = AppleHomeEditorialSeed(title, artist)

    @Test fun spreadsEightAppleProbesAcrossArtistsWithoutLosingSourcePriority() {
        val candidates = (1..12).map { seed("Release $it", "Artist A") } +
            listOf(seed("SUPER REAL ME", "ILLIT"),
                seed("EASY", "LE SSERAFIM"),
                seed("Get Up", "NewJeans"))
        val selected = AppleEditorialSeedPlanner.select(candidates, maxProbes = 8)
        assertEquals(8, selected.size)
        assertEquals(listOf("Release 1", "SUPER REAL ME", "EASY", "Get Up"),
            selected.take(4).map { it.title })
        assertEquals(listOf("Release 1", "SUPER REAL ME", "EASY", "Get Up",
            "Release 2", "Release 3", "Release 4", "Release 5"),
            selected.map { it.title })
    }

    @Test fun oneArtistRetainsOriginalOrderingAndCaseVariantsDoNotDoubleProbe() {
        val selected = AppleEditorialSeedPlanner.select(listOf(
            seed("EASY", "LE SSERAFIM"),
            seed("EASY - EP", "le sserafim"),
            seed("HOT", "LE SSERAFIM"),
            seed("Get Up", "NewJeans"),
        ), maxProbes = 3)
        assertEquals(listOf("EASY", "Get Up", "HOT"),
            selected.map { it.title })
        assertEquals(emptyList<AppleHomeEditorialSeed>(),
            AppleEditorialSeedPlanner.select(emptyList(), 8))
    }

    @Test fun unsafeTitlesAreRejectedBeforeAnyAppleSearchBudgetIsSpent() {
        val selected = AppleEditorialSeedPlanner.select(listOf(
            seed("Dance practice version", "ILLIT"),
            seed("Album\nnew line", "ILLIT"),
            seed("Valid Album", "ILLIT"),
            seed("Different Artist", "Band"),
            seed("Valid Album - EP", "ILLIT"),
        ), maxProbes = 5)
        assertEquals(listOf("Valid Album", "Different Artist"),
            selected.map { it.title })
    }

    @Test fun allFailedAppleRequestsStillProduceDiagnosticCoverageReport() =
        runBlocking {
            val collector = AppleHomeEditorialDiscovery(
                source = AppleHomeAlbumSearchSource { _, _ ->
                    AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
                },
            )
            val result = collector.discover(
                listOf(seed("SUPER REAL ME", "ILLIT"), seed("EASY", "LE SSERAFIM")),
                "br", maxProbes = 2,
            ) as AppleEditorialLoad.Failure
            assertEquals(ItunesCatalogFailure.UNAVAILABLE, result.reason)
            assertEquals(AppleEditorialProbeReport(
                eligibleSeeds = 2, probesMade = 2, matchingProducts = 0,
                withoutMatchingProducts = 0, failedRequests = 2,
                incompleteResponses = 0, budgetLimited = false,
            ), result.probeReport)
        }

    @Test fun reportMeasuresActualCallsAndNoMatchInsteadOfInventingCoverage() =
        runBlocking {
            val asked = mutableListOf<String>()
            val discovery = AppleHomeEditorialDiscovery(
                source = AppleHomeAlbumSearchSource { title, storefront ->
                    assertEquals("br", storefront)
                    asked += title
                    when (title) {
                        "SUPER REAL ME" -> AppleEditorialLoad.Success(
                            listOf(ItunesEditorialRelease(
                                appleScopedId = "itunes:br:album:101",
                                appleCollectionId = 101, storefront = "br",
                                title = "SUPER REAL ME - EP", artistName = "ILLIT",
                                artistId = 22, releaseDate = "2024-03-25",
                                genre = "K-Pop",
                                format = ItunesEditorialReleaseFormat.EP,
                                artworkUrl = null, trackCount = 5,
                                tracks = emptyList(),
                                musicBrainzReleaseGroupId = null,
                                musicBrainzArtistId = null,
                                musicBrainzRecordingIds = emptyMap(),
                            )), AppleEditorialReadState.COMPLETE,
                        )
                        "EASY" -> AppleEditorialLoad.Success(
                            emptyList(), AppleEditorialReadState.NOT_IN_STOREFRONT,
                        )
                        else -> AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE)
                    }
                }
            )
            val result = discovery.discover(
                listOf(seed("SUPER REAL ME", "ILLIT"),
                    seed("EASY", "LE SSERAFIM"),
                    seed("Get Up", "NewJeans"), seed("HOT", "LE SSERAFIM"),
                    seed("Another", "ILLIT")),
                "br", maxProbes = 3,
            ) as AppleEditorialLoad.Success
            assertEquals(listOf("SUPER REAL ME", "EASY", "Get Up"), asked)
            assertEquals(listOf(101L), result.releases.map { it.appleCollectionId })
            assertEquals(AppleEditorialProbeReport(
                eligibleSeeds = 5, probesMade = 3, matchingProducts = 1,
                withoutMatchingProducts = 1, failedRequests = 1,
                incompleteResponses = 0, budgetLimited = true,
            ), result.probeReport)
            assertEquals(AppleEditorialReadState.POSSIBLY_TRUNCATED, result.state)
        }
}
