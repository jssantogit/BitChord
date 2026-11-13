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

    @Test fun cappedArtistAutomaticallyFetchesMbTitleSuggestionsAfterBroadAppleSearch() =
        runBlocking {
            val fake = Fake(
                loaded(album(1), capped = true),
                loaded(album(1)),
                mutableMapOf(
                    "Already known" to loaded(),
                    "Apple only edition" to loaded(album(3, "Apple only edition")),
                ),
            )
            var hintCalls = 0
            val source = AppleArtistTitleHintSource { artist, verifiedId ->
                hintCalls++
                assertEquals("Artist", artist)
                assertNull(verifiedId) // Never synthesize MB Artist ID from name.
                listOf("Already known", "Apple only edition")
            }
            val pager = AppleArtistDiscographyPager(
                77, "Artist", "br", 1, fake, titleHintSource = source,
            )
            pager.start()
            val loaded = pager.loadMore() as AppleArtistPageLoad.Success
            assertEquals(1, hintCalls)
            assertEquals(2, loaded.snapshot.visible.size)
            assertEquals(listOf("Already known", "Apple only edition"), fake.searched)
            assertEquals(listOf(1L, 3L), loaded.snapshot.visible.map { it.appleCollectionId })
            assertTrue(loaded.snapshot.coverageUncertain)
        }

    @Test fun hintsWaitUntilLocalPagesAreConsumedAndNeverRepeatAfterExhaustion() =
        runBlocking {
            val fake = Fake(loaded(album(1), album(2), capped = true), loaded())
            var calls = 0
            val pager = AppleArtistDiscographyPager(
                77, "Artist", "br", 1, fake,
                titleHintSource = AppleArtistTitleHintSource { _, _ ->
                    calls++
                    listOf("Absent")
                },
            )
            pager.start()
            assertEquals(0, calls)
            pager.loadMore() // local album #2
            assertEquals(0, calls)
            pager.loadMore() // broad search, then one MB candidate probe
            assertEquals(1, calls)
            assertEquals(listOf("Absent"), fake.searched)
            assertFalse(pager.snapshot().hasMore)
            pager.loadMore()
            assertEquals(1, calls)
            assertEquals(1, fake.broadCalls)
        }

    @Test fun titleSuggestionsAlreadyPresentInAppleListAreNotProbedAgain() =
        runBlocking {
            val fake = Fake(
                loaded(album(1, "Known Album - EP"), capped = true),
                loaded(),
                mutableMapOf("New Edition" to loaded(album(2, "New Edition"))),
            )
            val pager = AppleArtistDiscographyPager(
                77, "Artist", "br", 1, fake,
                titleHintSource = AppleArtistTitleHintSource { _, _ ->
                    listOf("Known Album", "New Edition")
                },
            )
            pager.start()
            pager.loadMore()
            assertEquals(listOf("New Edition"), fake.searched)
            assertEquals(listOf(1L, 2L), pager.snapshot().visible.map { it.appleCollectionId })
        }

    @Test fun targetedSearchRevealsOnlyRequestedAlbumWithoutUnpagingEntireDiscography() =
        runBlocking {
            val fake = Fake(
                loaded(*(1L..50L).map { album(it) }.toTypedArray()),
                titles = mutableMapOf("New release" to loaded(album(51))),
            )
            val pager = AppleArtistDiscographyPager(77, "Artist", "br", 10, fake)
            pager.start()
            assertEquals((1L..10L).toList(), pager.snapshot().visible.map { it.appleCollectionId })

            val result = pager.searchMore("New release") as AppleArtistPageLoad.Success
            assertEquals(1, result.added)
            assertEquals((1L..10L).toList() + 51L,
                result.snapshot.visible.map { it.appleCollectionId })
            assertTrue(result.snapshot.hasMore)

            pager.loadMore()
            assertEquals((1L..10L).toList() + 51L + (11L..20L).toList(),
                pager.snapshot().visible.map { it.appleCollectionId })
        }

    @Test fun targetedSearchCanRevealAnAlreadyDiscoveredButNotYetVisibleAlbum() =
        runBlocking {
            val fake = Fake(
                loaded(*(1L..30L).map { album(it) }.toTypedArray()),
                titles = mutableMapOf("Hidden release" to loaded(album(29))),
            )
            val pager = AppleArtistDiscographyPager(77, "Artist", "br", 10, fake)
            pager.start()
            val result = pager.searchMore("Hidden release") as AppleArtistPageLoad.Success
            assertEquals(0, result.added)
            assertEquals((1L..10L).toList() + 29L,
                result.snapshot.visible.map { it.appleCollectionId })
            pager.loadMore()
            assertEquals((1L..10L).toList() + 29L + (11L..20L).toList(),
                pager.snapshot().visible.map { it.appleCollectionId })
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
