package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchFailure
import com.music.bitchord.ui.CanonicalSearchPageCursors
import com.music.bitchord.ui.canonicalArtistContinuationRows
import com.music.bitchord.ui.canonicalSearchRowsWithOfficialEditionTitles
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchPagingTest {
    @Test fun firstPageUsesRawConsumedCountNotVisibleCount() {
        val cursors = CanonicalSearchPageCursors.initial(
            query = "Magnetic",
            recordings = page(total = 100, offset = 0, raw = 25),
            groups = page(total = 5, offset = 0, raw = 5),
            releases = page(total = 31, offset = 0, raw = 15),
            artists = page(total = 0, offset = 0, raw = 0),
        )
        assertEquals(25, cursors.recordings)
        assertEquals(null, cursors.groups)
        assertEquals(15, cursors.releases)
        assertEquals(null, cursors.artists)
        assertTrue(cursors.hasMore)
    }

    @Test fun retriesFailedPageWithoutSkippingOffsetButCompletesSuccessfulKinds() {
        val first = CanonicalSearchPageCursors.initial(
            query = "aespa",
            recordings = page(40, 0, 20),
            groups = page(22, 0, 15),
            releases = page(0, 0, 0),
            artists = page(0, 0, 0),
        )
        val after = first.advance(
            recordings = MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE),
            groups = page(22, 15, 7),
        )
        assertEquals(20, after.recordings)
        assertEquals(null, after.groups)
        assertTrue(after.hasMore)
        val completed = after.advance(recordings = page(40, 20, 20))
        assertFalse(completed.hasMore)
    }

    @Test fun initialPartialFailureCanRetryMissingEntityTypeFromOffsetZero() {
        val failed = MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
        val first = CanonicalSearchPageCursors.initial(
            query = "ILLIT",
            recordings = page(total = 1, offset = 0, raw = 1),
            groups = failed,
            releases = page(total = 0, offset = 0, raw = 0),
            artists = page(total = 0, offset = 0, raw = 0),
        )
        assertEquals(0, first.groups)
        assertTrue(first.hasMore)
        val next = first.advance(groups = page(total = 30, offset = 0, raw = 15))
        assertEquals(15, next.groups)
    }

    @Test fun artistMbidScopePersistsAcrossContinuationsAndRetainsRawOffsets() {
        val first = CanonicalSearchPageCursors.initial(
            query = "ILLIT",
            recordings = page(50, 0, 25),
            groups = page(20, 0, 15),
            releases = page(10, 0, 10),
            artists = page(2, 0, 2),
            artistScopeMbid = "6b128dd7-e998-4edb-a4f5-400fef190445",
        )
        assertEquals("6b128dd7-e998-4edb-a4f5-400fef190445", first.artistScopeMbid)
        val next = first.advance(recordings = page(50, 25, 25), groups = page(20, 15, 5))
        assertEquals(first.artistScopeMbid, next.artistScopeMbid)
        assertEquals(null, next.recordings)
        assertEquals(null, next.groups)
    }

    @Test fun emptyOrExhaustedResponseStopsPagination() {
        val first = CanonicalSearchPageCursors.initial(
            query = "ILLIT",
            recordings = page(10, 0, 5),
            groups = page(0, 0, 0),
            releases = page(0, 0, 0),
            artists = page(0, 0, 0),
        )
        val stopped = first.advance(recordings = page(10, 5, 0))
        assertFalse(stopped.hasMore)
    }


    @Test fun artistProgressivePagesAppendWithoutLosingAlreadyPublishedAlbumsAndSingles() {
        // Artist browse first page can already have verified cover and official
        // "How Sweet" edition display name. Later pages must not erase it.
        val howSweet = CanonicalCatalogSearchRow(
            "9750354e-78df-4058-8758-b5a7a2eccf66",
            CanonicalSearchKind.RELEASE_GROUP, "How Sweet", "NewJeans",
            releaseKind = "Single", artworkUri = "https://images.example/how-sweet.jpg",
        )
        val getUp = CanonicalCatalogSearchRow(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            CanonicalSearchKind.RELEASE_GROUP, "Get Up", "NewJeans",
            releaseKind = "EP",
        )
        val next = CanonicalCatalogSearchRow(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            CanonicalSearchKind.RELEASE_GROUP, "Older Single", "NewJeans",
            releaseKind = "Single",
        )
        val combined = canonicalArtistContinuationRows(listOf(howSweet, getUp), listOf(next))

        assertEquals(listOf(howSweet.musicBrainzId, getUp.musicBrainzId, next.musicBrainzId),
            combined.map { it.musicBrainzId })
        assertEquals("How Sweet", combined[0].title)
        assertEquals("https://images.example/how-sweet.jpg", combined[0].artworkUri)
        assertEquals("EP", combined[1].releaseKind)
    }

    @Test fun artistPageOverlapCannotUndoVerifiedOfficialNameOrAddDuplicateWork() {
        val mbid = "9750354e-78df-4058-8758-b5a7a2eccf66"
        val prior = CanonicalCatalogSearchRow(
            mbid, CanonicalSearchKind.RELEASE_GROUP, "How Sweet", "NewJeans",
            releaseKind = "Single", artworkUri = "https://images.example/cover.jpg",
        )
        val rawAgain = prior.copy(
            title = "How Sweet & Bubble Gum", artworkUri = null,
        )
        val next = CanonicalCatalogSearchRow(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            CanonicalSearchKind.RELEASE_GROUP, "New Single", "NewJeans",
            releaseKind = "Single",
        )
        val result = canonicalArtistContinuationRows(
            canonicalArtistContinuationRows(listOf(prior), listOf(rawAgain, next)),
            listOf(rawAgain, next),
        )
        assertEquals(2, result.size)
        assertEquals("How Sweet", result.single { it.musicBrainzId == mbid }.title)
        assertEquals(prior.artworkUri, result.first().artworkUri)
    }


    @Test fun officialLinkedReleaseRenamesCompositeGroupInFirstSearchWithoutNewFetch() {
        val groupMbid = "9750354e-78df-4058-8758-b5a7a2eccf66"
        val group = CanonicalCatalogSearchRow(
            groupMbid, CanonicalSearchKind.RELEASE_GROUP,
            "How Sweet & Bubble Gum", "NewJeans", releaseKind = "Single",
        )
        val edition = MusicBrainzReleaseHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet", "XW",
            "2024-05-24", "NewJeans", status = "Official",
            releaseGroupMbid = groupMbid,
        )
        val result = canonicalSearchRowsWithOfficialEditionTitles(
            listOf(group), listOf(edition),
        )
        assertEquals("How Sweet", result.single().title)
        assertEquals(groupMbid, result.single().musicBrainzId)
        assertEquals("Single", result.single().releaseKind)
        assertEquals("How Sweet & Bubble Gum", group.title)
    }

    @Test fun searchCannotBorrowWrongGroupOrNonOfficialEditionTitle() {
        val groupMbid = "9750354e-78df-4058-8758-b5a7a2eccf66"
        val group = CanonicalCatalogSearchRow(
            groupMbid, CanonicalSearchKind.RELEASE_GROUP,
            "How Sweet & Bubble Gum", "NewJeans", releaseKind = "Single",
        )
        val official = MusicBrainzReleaseHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet", "XW",
            "2024-05-24", "NewJeans", status = "Official",
            releaseGroupMbid = groupMbid,
        )
        val candidates = listOf(
            official.copy(releaseGroupMbid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            official.copy(status = "Bootleg"),
            official.copy(isVideoOnlyMedia = true),
            official.copy(releaseGroupMbid = null),
            official.copy(title = "Bubble Gum"),
        )
        candidates.forEach { candidate ->
            assertEquals(group.title,
                canonicalSearchRowsWithOfficialEditionTitles(
                    listOf(group), listOf(candidate),
                ).single().title)
        }
    }

    @Test fun searchPaginationMayCorrectExistingCompositeCardWithoutASecondIdentity() {
        val groupMbid = "9750354e-78df-4058-8758-b5a7a2eccf66"
        val row = CanonicalCatalogSearchRow(
            groupMbid, CanonicalSearchKind.RELEASE_GROUP,
            "How Sweet & Bubble Gum", "NewJeans", releaseKind = "Single",
            artworkUri = "https://images.example/verified.jpg",
        )
        val edition = MusicBrainzReleaseHit(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "How Sweet", "KR",
            "2024-05-24", "NewJeans", status = "Official", releaseGroupMbid = groupMbid,
        )
        val rows = canonicalSearchRowsWithOfficialEditionTitles(
            canonicalArtistContinuationRows(listOf(row), emptyList()),
            listOf(edition),
        )
        assertEquals(1, rows.size)
        assertEquals("How Sweet", rows.single().title)
        assertEquals(row.artworkUri, rows.single().artworkUri)
    }

    private fun page(
        total: Int, offset: Int, raw: Int,
    ): MusicBrainzCatalogSearchResult.Success<String> =
        MusicBrainzCatalogSearchResult.Success(
            MusicBrainzCatalogPage(listOf("visible"), total, offset, consumedCount = raw),
        )
}
