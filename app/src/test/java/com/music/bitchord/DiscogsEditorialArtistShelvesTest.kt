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
