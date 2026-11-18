package com.music.bitchord

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.ui.catalogSearchHasUsableRows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchCoverageTest {
    @Test fun artistOnlyDoesNotPreventAlbumFallback() {
        assertFalse(catalogSearchHasUsableRows(
            SearchFilter.ALBUMS, artists = true, albums = false, songs = false,
        ))
        assertTrue(catalogSearchHasUsableRows(
            SearchFilter.ARTISTS, artists = true, albums = false, songs = false,
        ))
    }

    @Test fun albumOnlyDoesNotPreventSongFallback() {
        assertFalse(catalogSearchHasUsableRows(
            SearchFilter.SONGS, artists = false, albums = true, songs = false,
        ))
        assertTrue(catalogSearchHasUsableRows(
            SearchFilter.ALBUMS, artists = false, albums = true, songs = false,
        ))
    }

    @Test fun generalSearchAcceptsAnyNativeResultAndNoProviderMeansNoHits() {
        assertTrue(catalogSearchHasUsableRows(
            SearchFilter.ALL, artists = false, albums = false, songs = true,
        ))
        assertFalse(catalogSearchHasUsableRows(
            SearchFilter.ALL, artists = false, albums = false, songs = false,
        ))
    }

    @Test fun playlistFilterCannotUseSongAlbumArtistToBlockItsOwnSources() {
        assertFalse(catalogSearchHasUsableRows(
            SearchFilter.PLAYLISTS, artists = true, albums = true, songs = true,
        ))
    }
}
