package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApplePublicSearchPagerTest {
    private fun album(id: Long, country: String = "br", title: String = "Release $id") =
        ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM,
            id = id, storefront = country, title = title,
            artistName = "Artist", artistId = 77,
            collectionId = id, storeUrl = null, releaseDate = null,
            genre = null, durationMs = null, trackNumber = null, discNumber = null,
        )

    private class Fake(
        var initial: ItunesCatalogResult,
        val extras: MutableMap<ItunesCatalogSearchAttribute, ItunesCatalogResult> =
            mutableMapOf(),
    ) : ApplePublicSearchSource {
        val calls = mutableListOf<ItunesCatalogSearchAttribute?>()
        override suspend fun search(
            query: String, entity: ItunesCatalogEntity, country: String,
            attribute: ItunesCatalogSearchAttribute?,
        ): ItunesCatalogResult {
            calls.add(attribute)
            assertEquals("br", country)
            assertEquals("Artist", query)
            return if (attribute == null) initial
            else extras[attribute] ?: ItunesCatalogResult.Success(emptyList())
        }
    }

    @Test fun firstTwoHundredAreLocalPagesWithoutFictitiousOffsets() = runBlocking {
        val fake = Fake(ItunesCatalogResult.Success(
            (1L..200L).map { album(it) }, possiblyTruncated = true))
        val pager = ApplePublicSearchPager(
            query = "Artist", entity = ItunesCatalogEntity.ALBUM,
            country = "br", pageSize = 25, source = fake,
        )
        assertEquals(25, (pager.start() as ApplePublicSearchPageLoad.Success)
            .snapshot.visible.size)
        repeat(7) { pager.loadMore() }
        assertEquals(200, pager.snapshot().visible.size)
        assertEquals(listOf(null), fake.calls)
        assertTrue(pager.snapshot().hasMore)
    }

    @Test fun fallbackAlbumTermCanFindTheTwoHundredFirstAppleProduct() = runBlocking {
        val fake = Fake(
            ItunesCatalogResult.Success((1L..200L).map { album(it) }, true),
            mutableMapOf(
                ItunesCatalogSearchAttribute.ALBUM_TERM to ItunesCatalogResult.Success(
                    listOf(album(200), album(201), album(202, "us")), false),
            ),
        )
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
            "br", 50, fake)
        pager.start()
        repeat(4) { pager.loadMore() }
        assertEquals(201, pager.snapshot().visible.size)
        assertTrue(pager.snapshot().coverageUncertain)
        assertEquals(listOf(null, ItunesCatalogSearchAttribute.ALBUM_TERM), fake.calls)
    }

    @Test fun failureDoesNotDiscardResultsAndNextClickRetriesSameAttribute() = runBlocking {
        val fake = Fake(ItunesCatalogResult.Success(listOf(album(1)), true),
            mutableMapOf(ItunesCatalogSearchAttribute.ALBUM_TERM to
                ItunesCatalogResult.Failure(ItunesCatalogFailure.UNAVAILABLE)))
        val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
            "br", 1, fake)
        pager.start()
        assertTrue(pager.loadMore() is ApplePublicSearchPageLoad.Failure)
        assertEquals(1, pager.snapshot().visible.size)
        fake.extras[ItunesCatalogSearchAttribute.ALBUM_TERM] =
            ItunesCatalogResult.Success(listOf(album(2)))
        pager.loadMore()
        assertEquals(listOf(1L,2L), pager.snapshot().visible.map { it.id })
        assertEquals(2, fake.calls.count { it == ItunesCatalogSearchAttribute.ALBUM_TERM })
    }

    @Test fun invalidUserSearchQueryReturnsFailureInsteadOfCrashingConstructor() =
        runBlocking {
            val fake = Fake(ItunesCatalogResult.Success(emptyList()))
            val pager = ApplePublicSearchPager(
                query = "Invalid\\nName", entity = ItunesCatalogEntity.ALBUM,
                country = "br", source = fake,
            )
            assertTrue(pager.start() is ApplePublicSearchPageLoad.Failure)
            assertTrue(fake.calls.isEmpty())
        }

    @Test fun contradictorySnapshotsForSameAppleIdAreExcludedNotPickedByFirstRow() =
        runBlocking {
            val fake = Fake(
                ItunesCatalogResult.Success(listOf(album(1)), true),
                mutableMapOf(ItunesCatalogSearchAttribute.ALBUM_TERM to
                    ItunesCatalogResult.Success(listOf(album(1, title = "Different")))),
            )
            val pager = ApplePublicSearchPager("Artist", ItunesCatalogEntity.ALBUM,
                "br", 1, fake)
            pager.start()
            pager.loadMore()
            assertTrue(pager.snapshot().visible.isEmpty())
            assertTrue(pager.snapshot().coverageUncertain)
        }
}
