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

    @Test fun laterDuplicateGroupCannotOverwriteFirstEditorialIdentityOrType() = runBlocking {
        val firstGroup = group("a").copy(
            title = "Original release", primaryType = "EP",
            firstReleaseDate = "2024-03-25",
        )
        val conflictingLater = firstGroup.copy(
            title = "Unrelated later title", primaryType = "Album",
            firstReleaseDate = null,
        )
        val first = MusicBrainzCatalogPage(listOf(firstGroup), 3, 0, consumedCount = 1)
        val published = mutableListOf<List<MusicBrainzReleaseGroupHit>>()
        val outcome = MusicBrainzArtistDiscographyPager.collect(first,
            fetch = { offset ->
                assertEquals(1, offset)
                MusicBrainzCatalogGraphResult.Success(MusicBrainzCatalogPage(
                    listOf(conflictingLater, group("b")), 3, 1, consumedCount = 2,
                ))
            },
            onPage = { rows, _ -> published += rows },
        )
        assertEquals(2, outcome.items.size)
        assertEquals("Original release", outcome.items.first().title)
        assertEquals("EP", outcome.items.first().primaryType)
        assertEquals("2024-03-25", outcome.items.first().firstReleaseDate)
        assertEquals(outcome.items, published.single())
        assertTrue(outcome.complete)
    }

    @Test fun emptyFirstPageWithPositiveServerTotalIsNotProvenComplete() = runBlocking {
        val first = MusicBrainzCatalogPage(
            emptyList<MusicBrainzReleaseGroupHit>(),
            total = 20, offset = 0, consumedCount = 0,
        )
        val outcome = MusicBrainzArtistDiscographyPager.collect(first,
            fetch = { error("an empty raw page cannot supply a safe next cursor") },
            onPage = { _, _ -> error("no confirmed rows to publish") },
        )
        assertEquals(emptyList<MusicBrainzReleaseGroupHit>(), outcome.items)
        assertEquals(0, outcome.nextOffset)
        assertFalse(outcome.complete)
        val emptyArtist = MusicBrainzArtistDiscographyPager.collect(
            first.copy(total = 0),
            fetch = { error("the empty artist catalogue is already complete") },
            onPage = { _, _ -> error("nothing to publish") },
        )
        assertTrue(emptyArtist.complete)
        assertEquals(null, emptyArtist.nextOffset)
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
