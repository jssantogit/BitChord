package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleArtistDiscographyPagerTest {
    private fun album(id: Long, name: String = "Album $id") = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$id",
        appleCollectionId = id, storefront = "br",
        title = name, artistName = "Artist", artistId = 77,
        releaseDate = "2024-01-01", genre = "Pop",
        format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        artworkUrl = null, trackCount = null, tracks = emptyList(),
        musicBrainzReleaseGroupId = null,
        musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
    )
    private fun loaded(vararg items: ItunesEditorialRelease, capped: Boolean = false) =
        AppleEditorialLoad.Success(items.toList(),
            if (capped) AppleEditorialReadState.POSSIBLY_TRUNCATED
            else AppleEditorialReadState.COMPLETE)

    private inner class Fake(
        var first: AppleEditorialLoad,
        var broad: AppleEditorialLoad = AppleEditorialLoad.Success(
            emptyList(), AppleEditorialReadState.COMPLETE),
        val titles: MutableMap<String, AppleEditorialLoad> = mutableMapOf(),
    ) : AppleArtistAlbumDiscoverySource {
        var broadCalls = 0
        val searched = mutableListOf<String>()
        override suspend fun initial(id: Long, country: String): AppleEditorialLoad = first
        override suspend fun byArtist(
            artist: String, id: Long, country: String,
        ): AppleEditorialLoad {
            broadCalls++
            return broad
        }
        override suspend fun byTitle(
            title: String, id: Long, country: String,
        ): AppleEditorialLoad {
            searched += title
            return titles[title] ?: loaded()
        }
    }

    @Test fun localPagesKeepTwoHundredProductsAndDelayNetwork() = runBlocking {
        val fake = Fake(AppleEditorialLoad.Success(
            (1L..200L).map { album(it) }, AppleEditorialReadState.POSSIBLY_TRUNCATED))
        val pager = AppleArtistDiscographyPager(77, "Artist", "br", 25, fake)
        assertEquals(25, (pager.start() as AppleArtistPageLoad.Success).snapshot.visible.size)
        repeat(7) { pager.loadMore() }
        assertEquals(200, pager.snapshot().visible.size)
        assertEquals(0, fake.broadCalls)
        assertTrue(pager.snapshot().hasMore)
        assertTrue(pager.snapshot().coverageUncertain)
    }

    @Test fun beyondTwoHundredRecoversOnlyNewAppleIds() = runBlocking {
        val fake = Fake(AppleEditorialLoad.Success(
            (1L..200L).map { album(it) }, AppleEditorialReadState.POSSIBLY_TRUNCATED),
            loaded(album(200), album(201)))
        val pager = AppleArtistDiscographyPager(77, "Artist", "br", 50, fake)
        pager.start()
        repeat(4) { pager.loadMore() }
        assertEquals(201, pager.snapshot().visible.size)
        assertEquals(201, pager.snapshot().visible.map { it.appleCollectionId }.distinct().size)
        assertEquals(1, fake.broadCalls)
        assertTrue(pager.snapshot().coverageUncertain)
    }

    @Test fun failedDiscoveryRetainsPagesAndCanRetry() = runBlocking {
        val fake = Fake(loaded(album(1), capped = true),
            AppleEditorialLoad.Failure(ItunesCatalogFailure.UNAVAILABLE))
        val pager = AppleArtistDiscographyPager(77, "Artist", "br", 1, fake)
        pager.start()
        assertTrue(pager.loadMore() is AppleArtistPageLoad.Failure)
        assertEquals(listOf(1L), pager.snapshot().visible.map { it.appleCollectionId })
        fake.broad = loaded(album(2))
        pager.loadMore()
        assertEquals(2, pager.snapshot().visible.size)
        assertEquals(2, fake.broadCalls)
    }

    @Test fun musicBrainzTitleHintsNeverBecomeAppleProductsWithoutAppleProof() = runBlocking {
        val fake = Fake(loaded(album(1), capped = true), loaded(), mutableMapOf(
            "Absent" to loaded(),
            "Found" to loaded(album(3)),
        ))
        val pager = AppleArtistDiscographyPager(77, "Artist", "br", 1, fake)
        pager.enqueueTitleHints(listOf("Absent", "Found"))
        pager.start()
        pager.loadMore()
        pager.loadMore()
        assertEquals(listOf(1L, 3L), pager.snapshot().visible.map { it.appleCollectionId })
        assertEquals(listOf("Absent", "Found"), fake.searched)
    }

    @Test fun targetedDiscoveryCannotBorrowForeignArtistOrStorefront() = runBlocking {
        val fake = Fake(loaded(album(1)), titles = mutableMapOf(
            "Extra" to loaded(album(2).copy(artistId = 55),
                album(3).copy(storefront = "us", appleScopedId = "itunes:us:album:3"),
                album(4)),
        ))
        val pager = AppleArtistDiscographyPager(77, "Artist", "br", 10, fake)
        pager.start()
        pager.searchMore("Extra")
        assertEquals(listOf(1L, 4L), pager.snapshot().visible.map { it.appleCollectionId })
        fake.titles["Conflict"] = loaded(album(1, "Different"))
        pager.searchMore("Conflict")
        assertEquals(listOf(4L), pager.snapshot().visible.map { it.appleCollectionId })
        assertTrue(pager.snapshot().coverageUncertain)
        assertTrue(pager.snapshot().allowsTargetedSearch)
    }

    @Test fun malformedHintIsRejectedWithoutNetworkCall() = runBlocking {
        val fake = Fake(loaded())
        val pager = AppleArtistDiscographyPager(77, "Artist", "br", 10, fake)
        pager.enqueueTitleHints(listOf(" ", "a".repeat(125), "Bad\nName"))
        pager.start()
        assertEquals(0, pager.snapshot().pendingHints)
        assertTrue(pager.searchMore("Bad\nName") is AppleArtistPageLoad.InvalidQuery)
        assertTrue(fake.searched.isEmpty())
    }
}
