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
}
