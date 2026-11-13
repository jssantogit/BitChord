package com.music.bitchord.ui.screens

import com.music.bitchord.data.settings.SongSort
import java.util.Locale

/** Online release sort/filter preserves official track numbers and duplicate titles. */
internal fun canonicalMetadataAlbumVisibleIndices(
    rows: List<Pair<String, String?>>,
    query: String,
    sort: SongSort,
): List<Int> {
    val term = query.trim()
    val kept = rows.indices.filter { i ->
        term.isEmpty() || rows[i].first.contains(term, ignoreCase = true) ||
            rows[i].second?.contains(term, ignoreCase = true) == true
    }
    return when (sort) {
        SongSort.TITLE_ASC -> kept.sortedWith(
            compareBy<Int> { rows[it].first.lowercase(Locale.ROOT) }.thenBy { it },
        )
        SongSort.TITLE_DESC -> kept.sortedWith(
            compareByDescending<Int> { rows[it].first.lowercase(Locale.ROOT) }.thenBy { it },
        )
        // Exact date-added information is not available for metadata-only recordings.
        else -> kept
    }
}
