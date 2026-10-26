package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchFailure
import com.music.bitchord.ui.CanonicalSearchPageCursors
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

    private fun page(
        total: Int, offset: Int, raw: Int,
    ): MusicBrainzCatalogSearchResult.Success<String> =
        MusicBrainzCatalogSearchResult.Success(
            MusicBrainzCatalogPage(listOf("visible"), total, offset, consumedCount = raw),
        )
}
