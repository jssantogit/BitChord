package com.music.bitchord

import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.canonicalBrowseCardId
import com.music.bitchord.ui.canonicalExistingHomeShelves
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical #178: metadata must feed the EXISTING Home shelf/card UI. */
class CanonicalExistingHomeSurfaceTest {
    private val mbid = "b8ae79f4-9627-4c2d-a9ad-110d0a057d23"

    @Test fun preservesDownloadedCollectionsAndLocalPlaylistsAsOriginalBrowseCards() {
        val downloaded = ShelfItem("Saved offline", "Album", null, null, "local:download:99")
        val playlist = ShelfItem("Mixtape", "3 músicas", null, null, "local:playlist:100")
        val result = canonicalExistingHomeShelves(
            downloaded = listOf(downloaded),
            playlists = listOf(playlist),
            metadata = emptyList(),
            downloadedHeading = "Coleções baixadas",
            playlistHeading = "Playlists",
            cachedHeading = "Catálogo no dispositivo",
        )
        assertEquals(listOf("Coleções baixadas", "Playlists"), result.map { it.title })
        assertEquals(downloaded, result[0].items.single())
        assertEquals(playlist, result[1].items.single())
    }

    @Test fun cachedReleasesAreNavigableButNeverInventStreamsOrClaimEditorialArtists() {
        val metadata = listOf(
            CanonicalCatalogSearchRow(mbid, CanonicalSearchKind.RELEASE_GROUP, "Get Up", "NewJeans", fromOfflineCache = true),
            CanonicalCatalogSearchRow(mbid, CanonicalSearchKind.RELEASE, "Get Up", "NewJeans", fromOfflineCache = true),
            CanonicalCatalogSearchRow(mbid, CanonicalSearchKind.ARTIST, "Unexpected producer", null, fromOfflineCache = true),
            CanonicalCatalogSearchRow(mbid, CanonicalSearchKind.RECORDING, "Super Shy", "NewJeans", hasCatalogedAsset = true),
        )
        val result = canonicalExistingHomeShelves(
            emptyList(), emptyList(), metadata, "Downloads", "Playlists", "Salvo",
        )
        assertEquals(1, result.size)
        assertEquals(2, result.single().items.size)
        assertTrue(result.single().items.all { it.browseId?.startsWith("catalog:") == true })
        assertTrue(result.single().items.all { it.videoId == null })
        assertFalse(result.single().items.any { it.title == "Unexpected producer" })
        assertFalse(result.single().items.any { it.title == "Super Shy" })
    }

    @Test fun exactMusicBrainzIdentityIsEmbeddedInNavigationKeyNotInVideoId() {
        val row = CanonicalCatalogSearchRow(mbid, CanonicalSearchKind.RELEASE, "How Sweet", "NewJeans")
        assertEquals("catalog:RELEASE:$mbid", canonicalBrowseCardId(row))
        assertEquals(null, canonicalBrowseCardId(row.copy(kind = CanonicalSearchKind.RECORDING)))
    }
}
