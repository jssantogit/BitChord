package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
import com.music.bitchord.ui.screens.canonicalServerRowAsBrowseItem
import com.music.bitchord.ui.screens.canonicalRowsForFilter
import com.music.bitchord.ui.screens.canonicalRowsForQuery
import com.music.bitchord.ui.screens.canonicalDisplaySubtitle
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

    @Test fun exactArtistQueryPlacesIdentityBeforeTextualRecordingMatches() {
        val unrelated = CanonicalCatalogSearchRow("other-song", CanonicalSearchKind.RECORDING, "Illit", "Kristian Kristiansen")
        val owned = CanonicalCatalogSearchRow("real-song", CanonicalSearchKind.RECORDING, "Magnetic", "ILLIT")
        val artist = CanonicalCatalogSearchRow("artist-main", CanonicalSearchKind.ARTIST, "ILLIT", "South Korean girl group")
        val album = CanonicalCatalogSearchRow("real-album", CanonicalSearchKind.RELEASE_GROUP, "SUPER REAL ME", "ILLIT")
        val displayed = canonicalRowsForQuery(listOf(unrelated, owned, album, artist), "illit", SearchFilter.ALL)
        assertEquals("artist-main", displayed.first().musicBrainzId)
        assertTrue(displayed.any { it.musicBrainzId == "real-song" })
        assertTrue(displayed.any { it.musicBrainzId == "real-album" })
        assertTrue(displayed.none { it.musicBrainzId == "other-song" })
    }

    @Test fun artistFilterDoesNotMergeUnverifiedHomonymsAndDeduplicatesOnlySameMbid() {
        val primary = CanonicalCatalogSearchRow("artist-1", CanonicalSearchKind.ARTIST, "ILLIT", "South Korean girl group")
        val unrelated = CanonicalCatalogSearchRow("artist-2", CanonicalSearchKind.ARTIST, "Illit", "Unrelated artist")
        val displayed = canonicalRowsForQuery(listOf(primary, primary, unrelated), "illit", SearchFilter.ARTISTS)
        assertEquals(listOf("artist-1", "artist-2"), displayed.map { it.musicBrainzId })
    }

    @Test fun canonicalSearchSubtitlesAreMusicFacingNotInternalDiagnostics() {
        val song = CanonicalCatalogSearchRow("recording", CanonicalSearchKind.RECORDING,
            "Magnetic", "ILLIT", hasCatalogedAsset = false)
        val artist = CanonicalCatalogSearchRow("artist", CanonicalSearchKind.ARTIST,
            "ILLIT", "South Korean girl group")
        val release = CanonicalCatalogSearchRow("single", CanonicalSearchKind.RELEASE_GROUP,
            "Magnetic", "ILLIT · 2024-03-25", releaseKind = "Single")
        assertEquals("ILLIT", canonicalDisplaySubtitle(song, "Gravação"))
        assertEquals("Artista", canonicalDisplaySubtitle(artist, "Artista"))
        assertEquals("Single", canonicalDisplaySubtitle(release, "Álbum"))
    }
}
