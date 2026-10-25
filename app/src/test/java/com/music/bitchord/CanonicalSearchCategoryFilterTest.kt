package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
import com.music.bitchord.ui.screens.canonicalServerRowAsBrowseItem
import com.music.bitchord.ui.screens.canonicalRowsForFilter
import com.music.bitchord.ui.screens.canonicalRowAsBrowseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchCategoryFilterTest {
    private val rows = listOf(
        CanonicalCatalogSearchRow("rec", CanonicalSearchKind.RECORDING, "Magnetic", "ILLIT"),
        CanonicalCatalogSearchRow("group", CanonicalSearchKind.RELEASE_GROUP, "SUPER REAL ME", "ILLIT"),
        CanonicalCatalogSearchRow("edition", CanonicalSearchKind.RELEASE, "SUPER REAL ME", "ILLIT"),
        CanonicalCatalogSearchRow("artist", CanonicalSearchKind.ARTIST, "ILLIT", null),
    )

    @Test fun artistsShowsArtistsOnly() {
        assertEquals(listOf("artist"), canonicalRowsForFilter(rows, SearchFilter.ARTISTS).map { it.musicBrainzId })
    }
    @Test fun albumsShowsGroupsAndEditionsButNotTrackRecordings() {
        assertEquals(listOf("group","edition"), canonicalRowsForFilter(rows, SearchFilter.ALBUMS).map { it.musicBrainzId })
    }
    @Test fun songsAndAllRemainDistinct() {
        assertEquals(listOf("rec"), canonicalRowsForFilter(rows, SearchFilter.SONGS).map { it.musicBrainzId })
        assertEquals(rows, canonicalRowsForFilter(rows, SearchFilter.ALL))
    }
    @Test fun unsupportedVideoAndPlaylistCategoriesNeverPretendMusicBrainzMediaExists() {
        assertTrue(canonicalRowsForFilter(rows, SearchFilter.VIDEOS).isEmpty())
        assertTrue(canonicalRowsForFilter(rows, SearchFilter.PLAYLISTS).isEmpty())
    }
    @Test fun serverTracksReuseNativeBrowseRowsWithArtworkAndOpaqueMediaIdentity() {
        val row = CanonicalServerSearchRow(
            recordingId = "recording-1", mediaAssetId = "asset-1",
            providerKey = "opensubsonic:demo", title = "Magnetic", artistDisplay = "ILLIT",
            artworkUri = "https://example.com/art.jpg",
        )
        val item = canonicalServerRowAsBrowseItem(row)
        assertEquals(BrowseType.OTHER, item.type)
        assertEquals(row.title, item.title)
        assertEquals(row.artistDisplay, item.subtitle)
        assertEquals(row.artworkUri, item.thumbnailUrl)
        assertTrue(item.browseId.contains("asset-1"))
        assertTrue(!item.browseId.contains("https://"))
    }

    @Test fun metadataRowsReuseNativeBrowseIdentityAndLayoutWithoutVideoIds() {
        val artist = canonicalRowAsBrowseItem(rows.last())
        val album = canonicalRowAsBrowseItem(rows[1])
        assertEquals(BrowseType.ARTIST, artist.type)
        assertEquals(BrowseType.ALBUM, album.type)
        assertEquals("catalog:ARTIST:artist", artist.browseId)
        assertEquals("catalog:RELEASE_GROUP:group", album.browseId)
        assertEquals("ILLIT", artist.title)
    }
}
