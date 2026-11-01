package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistDiscographyPager
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchFailure
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzArtistDiscographyPagerTest {
    private fun group(id: String) = MusicBrainzReleaseGroupHit(
        id, "Release $id", "EP", "2023-01-01", "Artist")
    @Test fun consumesActualServerOffsetsAndDeduplicatesAcrossPages() = runBlocking {
        val first = MusicBrainzCatalogPage(listOf(group("a")), 7, 0, consumedCount = 3)
        val calls = mutableListOf<Int>()
        val publications = mutableListOf<List<String>>()
        val outcome = MusicBrainzArtistDiscographyPager.collect(
            first, maxAdditionalPages = 4,
            fetch = { offset ->
                calls += offset
                MusicBrainzCatalogGraphResult.Success(when (offset) {
                    3 -> MusicBrainzCatalogPage(listOf(group("a"), group("b")), 7, 3, 2)
                    5 -> MusicBrainzCatalogPage(listOf(group("c")), 7, 5, 2)
                    else -> error("unexpected offset")
                })
            },
            onPage = { rows, next -> publications += rows.map { it.musicBrainzId } },
        )
        assertEquals(listOf(3, 5), calls)
        assertEquals(listOf(listOf("a", "b"), listOf("a", "b", "c")), publications)
        assertEquals(listOf("a", "b", "c"), outcome.items.map { it.musicBrainzId })
        assertEquals(null, outcome.nextOffset)
        assertTrue(outcome.complete)
    }

    @Test fun boundedContinuationLeavesExplicitCursorAndDoesNotClaimCompleteness() = runBlocking {
        val first = MusicBrainzCatalogPage(listOf(group("a")), 300, 0, consumedCount = 75)
        val calls = mutableListOf<Int>()
        val outcome = MusicBrainzArtistDiscographyPager.collect(first, maxAdditionalPages = 2,
            fetch = { offset ->
                calls += offset
                MusicBrainzCatalogGraphResult.Success(
                    MusicBrainzCatalogPage(listOf(group("x$offset")), 300, offset, 75))
            },
            onPage = { _, _ -> },
        )
        assertEquals(listOf(75, 150), calls)
        assertEquals(225, outcome.nextOffset)
        assertFalse(outcome.complete)
    }

    @Test fun serverFailureNeverDeletesExistingGroupsOrChangesThePaginationCursor() = runBlocking {
        val first = MusicBrainzCatalogPage(listOf(group("a")), 200, 0, consumedCount = 75)
        val outcome = MusicBrainzArtistDiscographyPager.collect(first, 4,
            fetch = { MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE) },
            onPage = { _, _ -> error("should not publish failure") },
        )
        assertEquals(listOf("a"), outcome.items.map { it.musicBrainzId })
        assertEquals(75, outcome.nextOffset)
        assertFalse(outcome.complete)
    }

    @Test fun upstreamOffsetMismatchOrEmptyPageHaltsWithoutInfiniteLoop() = runBlocking {
        val first = MusicBrainzCatalogPage(listOf(group("a")), 200, 0, consumedCount = 75)
        val outcome = MusicBrainzArtistDiscographyPager.collect(first, 10,
            fetch = { MusicBrainzCatalogGraphResult.Success(
                MusicBrainzCatalogPage(listOf(group("other")), 200, 0, 1)) },
            onPage = { _, _ -> error("should not publish wrong cursor") },
        )
        assertEquals(75, outcome.nextOffset)
        assertEquals(listOf("a"), outcome.items.map { it.musicBrainzId })
    }
}
