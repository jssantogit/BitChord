package com.music.bitchord.ui.screens

import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.catalog.CanonicalEntitySortDateRow
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalRecentAdditionsFiltersTest {
    private val rows = listOf(
        V2DisplayRow(id = "new", title = "Zigzag", artist = "AtHeart"),
        V2DisplayRow(id = "old", title = "Butterfly Doors", artist = "AtHeart"),
        V2DisplayRow(id = "unknown", title = "Dot Dot Dot", artist = "AtHeart"),
    )
    private val rating = CanonicalRatingDisplaySnapshot(
        byRecordingId = mapOf("old" to RatingDisplay(localValue = 5.0)),
    )
    private val times = mapOf(
        "new" to CanonicalEntitySortDateRow(
            entityId = "new", dateAddedSeconds = 30, dateModifiedSeconds = 10,
        ),
        "old" to CanonicalEntitySortDateRow(
            entityId = "old", dateAddedSeconds = 20, dateModifiedSeconds = 40,
        ),
        "unknown" to CanonicalEntitySortDateRow(
            entityId = "unknown", dateAddedSeconds = 10, dateModifiedSeconds = null,
        ),
    )

    @Test fun nativeFilterUsesCanonicalStarsAndLocalSourceWithoutFabricatingRemoteRecents() {
        val rated = filteredCanonicalRecentLocalAdditions(
            rows, "", LocalMusicSort.DATE_ADDED, "local-mediastore", 5, rating, times,
        )
        assertEquals(listOf("old"), rated.map { it.id })
        assertTrue(filteredCanonicalRecentLocalAdditions(
            rows, "", LocalMusicSort.DATE_ADDED, "opensubsonic:account", null, rating, times,
        ).isEmpty())
    }

    @Test fun searchAndTitleSortDoNotRequeryTheMediaStoreSnapshot() {
        val sorted = filteredCanonicalRecentLocalAdditions(
            rows, "AtHeart", LocalMusicSort.TITLE_ASC, null, null, rating, times,
        )
        assertEquals(listOf("old", "unknown", "new"), sorted.map { it.id })
        assertEquals(listOf("old"), filteredCanonicalRecentLocalAdditions(
            rows, "Butterfly", LocalMusicSort.TITLE_ASC, null, null, rating, times,
        ).map { it.id })
    }

    @Test fun factualModificationDatesSortUnknownValuesLast() {
        val sorted = filteredCanonicalRecentLocalAdditions(
            rows, "", LocalMusicSort.DATE_MODIFIED, null, null, rating, times,
        )
        assertEquals(listOf("old", "new", "unknown"), sorted.map { it.id })
    }
}
