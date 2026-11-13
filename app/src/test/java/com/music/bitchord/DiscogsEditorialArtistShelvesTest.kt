package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.discogsEditorialArtistShelves
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class DiscogsEditorialArtistShelvesTest {
    private fun row(id: String, kind: String) = CanonicalCatalogSearchRow(
        musicBrainzId = id, kind = CanonicalSearchKind.RELEASE_GROUP,
        title = "Release $id", subtitle = "Artist", releaseKind = kind,
        releaseDate = "2026-02-01",
    )
    private val album = row("00000000-0000-4000-8000-000000000101", "Album")
    private val unknown = row("00000000-0000-4000-8000-000000000102", "Other")
    private val ep = row("00000000-0000-4000-8000-000000000103", "EP")

    @Test fun exactMusicBrainzHotPairStillCollapsesWhenArtistGroupRowsAreNotCached() {
        // Native shelves can arrive before the independent public browse
        // exposes its current rows. The two typed original MBIDs are enough
        // because their single/EP relation was independently established.
        val singleId = "44adb7df-66bd-4ebe-a43a-3ea45a93529d"
        val epId = "50bcea80-692f-4e36-a7a5-825a3233cb13"
        val single = ShelfItem("HOT", "Single", null, null,
            "catalog:RELEASE_GROUP:$singleId")
        val ep = ShelfItem("HOT", "EP", null, null,
            "catalog:RELEASE_GROUP:$epId")
        val english = ShelfItem("HOT (English ver.)", "Single", null, null,
            "catalog:RELEASE_GROUP:00000000-0000-4000-8000-000000000404")
        val originals = listOf(HomeShelf("Singles e EPs", listOf(single, ep, english)))
        assertEquals(listOf(HomeShelf("Singles e EPs", listOf(ep, english))),
            discogsEditorialArtistShelves(originals, emptyList(), emptyMap()))
        val wrongParentType = listOf(HomeShelf("Singles e EPs", listOf(
            single, ep.copy(subtitle = "Single"), english)))
        assertEquals(wrongParentType,
            discogsEditorialArtistShelves(wrongParentType, emptyList(), emptyMap()))
    }

    @Test fun expandedArtistGridMustUseCurrentProjectionNotOldClickedShelfSnapshot() {
        val stale = HomeShelf("Singles e EPs", listOf(
            ShelfItem("HOT", "Single", null, null, "catalog:RELEASE_GROUP:44adb7df-66bd-4ebe-a43a-3ea45a93529d"),
            ShelfItem("HOT", "EP", null, null, "catalog:RELEASE_GROUP:50bcea80-692f-4e36-a7a5-825a3233cb13"),
        ))
        val current = HomeShelf("Singles e EPs", stale.items.drop(1))
        assertEquals(current, com.music.bitchord.ui.canonicalArtistCurrentExpandedShelf(
            stale, listOf(current)))
        assertNull(com.music.bitchord.ui.canonicalArtistCurrentExpandedShelf(stale, emptyList()))
        val discovery = HomeShelf("Para descobrir", stale.items)
        assertEquals(discovery, com.music.bitchord.ui.canonicalArtistCurrentExpandedShelf(
            discovery, listOf(current)))
    }

    @Test fun duplicateNamedBoompalaRemixesMustDisplayRealMusicBrainzCollaborators() {
        fun row(id: String, credited: String) = CanonicalCatalogSearchRow(
            musicBrainzId = id, kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "BOOMPALA", subtitle = credited, releaseKind = "Single",
            secondaryTypes = "Remix", releaseDate = "2026-07-24",
        )
        val groups = listOf(
            row("00000000-0000-4000-8000-000000000301", "LE SSERAFIM feat. SANTOS BRAVOS"),
            row("00000000-0000-4000-8000-000000000302", "LE SSERAFIM feat. Ayra Starr"),
            row("00000000-0000-4000-8000-000000000303", "LE SSERAFIM feat. GURU RANDHAWA"),
            row("00000000-0000-4000-8000-000000000304", "LE SSERAFIM"),
        )
        val original = listOf(HomeShelf("Singles e EPs", groups.map {
            ShelfItem("BOOMPALA", "Single · Remix", null, null,
                "catalog:RELEASE_GROUP:${it.musicBrainzId}")
        }))
        val output = com.music.bitchord.ui.canonicalArtistDisambiguateCreditedCollisions(
            original, groups, "LE SSERAFIM",
        )
        val cards = output.single().items
        assertEquals(4, cards.size)
        assertEquals("BOOMPALA (feat. SANTOS BRAVOS)", cards[0].title)
        assertEquals("BOOMPALA (feat. Ayra Starr)", cards[1].title)
        assertEquals("BOOMPALA (feat. GURU RANDHAWA)", cards[2].title)
        assertEquals("BOOMPALA", cards[3].title)
        assertEquals(original.single().items.map { it.browseId }, cards.map { it.browseId })
        assertTrue(cards.all { it.subtitle == "Single · Remix" })
    }

    @Test fun identicalTitleWithoutSpecificArtistCreditMustNotInventAnEdition() {
        val a = CanonicalCatalogSearchRow(
            "00000000-0000-4000-8000-000000000305",
            CanonicalSearchKind.RELEASE_GROUP, "Same", "LE SSERAFIM",
            releaseKind = "Single", secondaryTypes = "Remix",
        )
        val b = a.copy(musicBrainzId = "00000000-0000-4000-8000-000000000306")
        val shelves = listOf(HomeShelf("Singles e EPs", listOf(a,b).map {
            ShelfItem("Same", "Single · Remix", null, null,
                "catalog:RELEASE_GROUP:${it.musicBrainzId}")
        }))
        assertEquals(shelves,
            com.music.bitchord.ui.canonicalArtistDisambiguateCreditedCollisions(
                shelves, listOf(a,b), "LE SSERAFIM"))
    }

    @Test fun musicBrainzLinkedHotTitleSingleCollapsesOnlyOnArtistWhenParentEpCardPresent() {
        // Exact public MusicBrainz release-group relation:
        // HOT Single 44adb7df -> HOT EP 50bcea80 (single/EP taken from).
        val singleId = "44adb7df-66bd-4ebe-a43a-3ea45a93529d"
        val epId = "50bcea80-692f-4e36-a7a5-825a3233cb13"
        val englishSingleId = "00000000-0000-4000-8000-000000000201"
        val englishEpId = "00000000-0000-4000-8000-000000000202"
        val memberRemixId = "00000000-0000-4000-8000-000000000203"
        fun group(id: String, title: String, kind: String, extra: String? = null) =
            CanonicalCatalogSearchRow(
                musicBrainzId = id, kind = CanonicalSearchKind.RELEASE_GROUP,
                title = title, subtitle = "LE SSERAFIM", releaseKind = kind,
                secondaryTypes = extra, releaseDate = "2025-03-14",
            )
        val groups = listOf(
            group(singleId, "HOT", "Single"), group(epId, "HOT", "EP"),
            group(englishSingleId, "HOT (English ver.)", "Single"),
            group(englishEpId, "HOT (English ver.)", "EP"),
            group(memberRemixId, "HOT (KAZUHA ver.)", "Single", "Remix"),
        )
        val cards = groups.map { row ->
            ShelfItem(row.title, row.releaseKind.orEmpty(), null, null,
                "catalog:RELEASE_GROUP:${row.musicBrainzId}")
        }
        val first = listOf(HomeShelf("Singles e EPs", cards))
        val displayed = discogsEditorialArtistShelves(first, groups, emptyMap())
        val items = displayed.single().items
        assertEquals(4, items.size)
        assertTrue(items.none { it.browseId == "catalog:RELEASE_GROUP:$singleId" })
        assertTrue(items.any { it.browseId == "catalog:RELEASE_GROUP:$epId" })
        assertTrue(items.any { it.browseId == "catalog:RELEASE_GROUP:$memberRemixId" })
        assertEquals(2, items.count { it.title == "HOT (English ver.)" })
        // Canonical rows are still all present: only the artist UI projection changes.
        assertEquals(5, groups.size)
    }

    @Test fun aSourceLinkedSingleStillBelongsToUnrelatedDiscoveryShelf() {
        // Only the artist's typed discography grid is being consolidated.
        // An explicit selection on another shelf is not this projection.
        val singleId = "44adb7df-66bd-4ebe-a43a-3ea45a93529d"
        val epId = "50bcea80-692f-4e36-a7a5-825a3233cb13"
        val single = CanonicalCatalogSearchRow(singleId,
            CanonicalSearchKind.RELEASE_GROUP, "HOT", "LE SSERAFIM",
            releaseKind = "Single")
        val ep = single.copy(musicBrainzId = epId, releaseKind = "EP")
        val picked = ShelfItem("HOT", "Single", null, null,
            "catalog:RELEASE_GROUP:$singleId")
        val parent = ShelfItem("HOT", "EP", null, null,
            "catalog:RELEASE_GROUP:$epId")
        val original = listOf(
            HomeShelf("Para descobrir", listOf(picked)),
            HomeShelf("Singles e EPs", listOf(picked, parent)),
        )
        val out = discogsEditorialArtistShelves(original,
            listOf(single, ep), emptyMap())
        assertEquals(listOf(picked), out.first().items)
        assertEquals(listOf(parent), out.last().items)
    }

    @Test fun linkedHotSingleMustStayWhenParentOrTypedRelationNotVisible() {
        val singleId = "44adb7df-66bd-4ebe-a43a-3ea45a93529d"
        val epId = "50bcea80-692f-4e36-a7a5-825a3233cb13"
        val hot = CanonicalCatalogSearchRow(
            singleId, CanonicalSearchKind.RELEASE_GROUP, "HOT",
            "LE SSERAFIM", releaseKind = "Single",
        )
        val otherArtist = hot.copy(musicBrainzId = epId, subtitle = "Other Artist",
            releaseKind = "EP")
        val cards = listOf(HomeShelf("Singles e EPs", listOf(
            ShelfItem("HOT", "Single", null, null, "catalog:RELEASE_GROUP:$singleId")
        )))
        assertEquals(cards, discogsEditorialArtistShelves(cards, listOf(hot), emptyMap()))
        assertEquals(cards, discogsEditorialArtistShelves(
            cards, listOf(hot, otherArtist), emptyMap()))
        val noParentCard = discogsEditorialArtistShelves(
            cards, listOf(hot, hot.copy(musicBrainzId = epId, releaseKind = "EP")),
            emptyMap())
        assertEquals(cards, noParentCard)
        val wrongType = discogsEditorialArtistShelves(
            listOf(HomeShelf("Singles e EPs", cards.single().items +
                ShelfItem("HOT", "EP", null, null, "catalog:RELEASE_GROUP:$epId"))),
            listOf(hot, hot.copy(musicBrainzId = epId, releaseKind = "Single")),
            emptyMap(),
        )
        assertEquals(2, wrongType.single().items.size)
    }

    @Test fun provenCorrectionMovesAlbumToNativeSinglesAndEpsWithoutChangingIdentity() {
        val groups = listOf(album, ep, unknown)
        val first = HomeShelf("Álbuns", listOf(
            ShelfItem(album.title, "Album", "cover://original", null,
                "catalog:RELEASE_GROUP:${album.musicBrainzId}"),
        ))
        val second = HomeShelf("Singles e EPs", listOf(
            ShelfItem(ep.title, "EP", null, null, "catalog:RELEASE_GROUP:${ep.musicBrainzId}"),
        ))
        val evidence = mapOf(
            album.musicBrainzId to listOf(
                DiscogsGroupTypeProof(11, DiscogsEditorialKind.EP, true),
                DiscogsGroupTypeProof(12, DiscogsEditorialKind.EP, true),
            ),
            unknown.musicBrainzId to listOf(
                DiscogsGroupTypeProof(13, DiscogsEditorialKind.SINGLE, true),
            ),
        )
        val resulting = discogsEditorialArtistShelves(listOf(first, second), groups, evidence)
        assertTrue(resulting.none { it.title == "Álbuns" && it.items.isNotEmpty() })
        val singles = resulting.first { it.title == "Singles e EPs" }.items
        assertEquals(3, singles.size)
        assertEquals(3, singles.map { it.browseId }.distinct().size)
        assertTrue(singles.any { it.browseId == "catalog:RELEASE_GROUP:${album.musicBrainzId}" &&
            it.subtitle.startsWith("EP") && it.thumbnailUrl == "cover://original" })
        assertTrue(singles.any { it.browseId == "catalog:RELEASE_GROUP:${unknown.musicBrainzId}" &&
            it.subtitle.startsWith("Single") })
    }

    @Test fun homeAndExploreRowsOnlyChangeDerivedKindAfterIndependentConsensus() {
        val rows = listOf(album, ep, unknown)
        val evidence = mapOf(
            album.musicBrainzId to listOf(
                DiscogsGroupTypeProof(41, DiscogsEditorialKind.EP, true),
                DiscogsGroupTypeProof(42, DiscogsEditorialKind.EP, true),
            ),
            unknown.musicBrainzId to listOf(
                DiscogsGroupTypeProof(43, DiscogsEditorialKind.ALBUM, true),
            ),
        )
        val projected = com.music.bitchord.ui.discogsEditorialProjectedRows(rows, evidence)
        assertEquals(listOf("EP", "EP", "Album"), projected.map { it.releaseKind })
        assertEquals(rows.map { it.musicBrainzId }, projected.map { it.musicBrainzId })
        assertEquals(rows.map { it.title }, projected.map { it.title })
        assertEquals("Album", album.releaseKind)
        assertEquals("Other", unknown.releaseKind)
        assertEquals(rows, com.music.bitchord.ui.discogsEditorialProjectedRows(rows, emptyMap()))
    }

    @Test fun unknownGroupOrSingleConflictingProofCannotChangeReleaseClassification() {
        val evidence = mapOf(album.musicBrainzId to listOf(
            DiscogsGroupTypeProof(4, DiscogsEditorialKind.SINGLE, true),
        ), "unrelated-group" to listOf(
            DiscogsGroupTypeProof(5, DiscogsEditorialKind.EP, true),
            DiscogsGroupTypeProof(6, DiscogsEditorialKind.EP, true),
        ))
        assertEquals(listOf(album, ep),
            com.music.bitchord.ui.discogsEditorialProjectedRows(listOf(album, ep), evidence))
    }

    @Test fun unrelatedDiscoveryShelvesDoNotLeakTheirCardsIntoTypedArtistShelves() {
        val duplicate = ShelfItem(ep.title, "EP", null, null,
            "catalog:RELEASE_GROUP:${ep.musicBrainzId}")
        val discovery = HomeShelf("Para descobrir", listOf(duplicate))
        val albums = HomeShelf("Álbuns", listOf(
            ShelfItem(album.title, "Album", null, null,
                "catalog:RELEASE_GROUP:${album.musicBrainzId}"),
        ))
        val overrides = mapOf(album.musicBrainzId to listOf(
            DiscogsGroupTypeProof(41, DiscogsEditorialKind.EP, true),
            DiscogsGroupTypeProof(42, DiscogsEditorialKind.EP, true),
        ))
        val result = discogsEditorialArtistShelves(
            listOf(discovery, albums), listOf(album, ep), overrides,
        )
        assertEquals(listOf(duplicate),
            result.first { it.title == "Para descobrir" }.items)
        val typed = result.first { it.title == "Singles e EPs" }.items
        assertEquals(1, typed.size)
        assertEquals("catalog:RELEASE_GROUP:${album.musicBrainzId}", typed.single().browseId)
    }

    @Test fun partialMusicBrainzPageMustNotDropExistingArtistCardsOnCorrection() {
        val notOnCurrentPage = ShelfItem("Previously loaded album", "Album",
            "cover://preserve", null,
            "catalog:RELEASE_GROUP:00000000-0000-4000-8000-000000000888")
        val original = listOf(
            HomeShelf("Álbuns", listOf(
                ShelfItem(album.title, "Album", null, null,
                    "catalog:RELEASE_GROUP:${album.musicBrainzId}"),
                notOnCurrentPage,
            )),
            HomeShelf("Singles e EPs", listOf(
                ShelfItem(ep.title, "EP", null, null,
                    "catalog:RELEASE_GROUP:${ep.musicBrainzId}"),
            )),
        )
        val proofs = mapOf(album.musicBrainzId to listOf(
            DiscogsGroupTypeProof(10, DiscogsEditorialKind.EP, true),
            DiscogsGroupTypeProof(11, DiscogsEditorialKind.EP, true),
        ))
        val result = discogsEditorialArtistShelves(
            original, listOf(album), proofs,
        )
        assertEquals(listOf(notOnCurrentPage),
            result.first { it.title == "Álbuns" }.items)
        assertTrue(result.first { it.title == "Singles e EPs" }.items
            .any { it.browseId == "catalog:RELEASE_GROUP:${ep.musicBrainzId}" })
        assertEquals(3, result.flatMap { it.items }.size)
    }

    @Test fun oneContraryEditionNeverChangesExistingAlbumShelf() {
        val original = listOf(HomeShelf("Álbuns", listOf(
            ShelfItem(album.title, "Album", null, null,
                "catalog:RELEASE_GROUP:${album.musicBrainzId}"),
        )))
        val evidence = mapOf(album.musicBrainzId to listOf(
            DiscogsGroupTypeProof(11, DiscogsEditorialKind.EP, true),
        ))
        assertEquals(original, discogsEditorialArtistShelves(original, listOf(album), evidence))
    }

    @Test fun expandedShelfTransitionKeyIsStableAcrossProgressiveCoverAndPaginationUpdates() {
        // The real A54 shelf receives many updates while MusicBrainz and CAA
        // deliver artist pages. AnimatedContent must NOT animate/reset the grid
        // each time a new HomeShelf instance arrives for the same section.
        val artistId = "catalog:ARTIST:00000000-0000-4000-8000-000000000777"
        val first = HomeShelf("Álbuns", listOf(
            ShelfItem("First", "Album", null, null,
                "catalog:RELEASE_GROUP:00000000-0000-4000-8000-000000000701"),
        ))
        val withCover = first.copy(items = first.items.map {
            it.copy(thumbnailUrl = "https://example.org/cover.jpg")
        })
        val paginated = withCover.copy(items = withCover.items + ShelfItem(
            "Second", "Album", null, null,
            "catalog:RELEASE_GROUP:00000000-0000-4000-8000-000000000702",
        ))
        val key = com.music.bitchord.ui.canonicalArtistExpandedShelfContentKey(artistId, first)
        assertNotNull(key)
        assertEquals(key,
            com.music.bitchord.ui.canonicalArtistExpandedShelfContentKey(artistId, withCover))
        assertEquals(key,
            com.music.bitchord.ui.canonicalArtistExpandedShelfContentKey(artistId, paginated))
        assertNotEquals(key, com.music.bitchord.ui.canonicalArtistExpandedShelfContentKey(
            artistId, HomeShelf("Singles e EPs", paginated.items)))
        assertNotEquals(key, com.music.bitchord.ui.canonicalArtistExpandedShelfContentKey(
            "catalog:ARTIST:00000000-0000-4000-8000-000000000778", paginated))
        assertNull(com.music.bitchord.ui.canonicalArtistExpandedShelfContentKey(artistId, null))
    }

}
