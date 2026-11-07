package com.music.bitchord.ui.screens

import com.music.bitchord.data.settings.SongSort
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalMetadataAlbumSortTest {
    @Test fun queryAndSortKeepOriginalTrackIndicesEvenWithDuplicateNames() {
        val rows = listOf("UFO" to "Artist", "Bad Idea" to "Artist",
            "UFO" to "Artist", "Last" to "Other")
        assertEquals(listOf(0, 2), canonicalMetadataAlbumVisibleIndices(rows, "ufo", SongSort.DEFAULT))
        assertEquals(listOf(1, 3, 0, 2),
            canonicalMetadataAlbumVisibleIndices(rows, "", SongSort.TITLE_ASC))
        assertEquals(listOf(0, 2, 3, 1),
            canonicalMetadataAlbumVisibleIndices(rows, "", SongSort.TITLE_DESC))
        assertEquals(listOf(0, 1, 2, 3),
            canonicalMetadataAlbumVisibleIndices(rows, "", SongSort.DATE_ADDED_DESC))
    }
}
