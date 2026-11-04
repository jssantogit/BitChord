package com.music.bitchord.ui.screens

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.trackColumnWidth

private const val ARTIST_TRACK_LIMIT = 20
private const val ARTIST_TRACK_ROWS_PER_COLUMN = 4

/**
 * Artist carousel geometry comes from the existing DetailScreen, not a
 * separate, source-specific implementation. Filtering and playback identity
 * remain the caller's responsibility.
 */
internal fun <T> nativeArtistTrackColumns(tracks: List<T>): List<List<T>> =
    tracks.take(ARTIST_TRACK_LIMIT).chunked(ARTIST_TRACK_ROWS_PER_COLUMN)

/**
 * One visual track strip shared by DetailScreen and the unified Library.
 * Callers provide their own row, so canonical RecordingIds need never be
 * converted into fake Song.videoIds to preserve ratings, menus or selection.
 */
@Composable
internal fun <T> NativeArtistTracksCarousel(
    tracks: List<T>,
    horizontalPadding: Dp = PAGE_GUTTER,
    itemKey: ((T) -> Any)? = null,
    row: @Composable (T) -> Unit,
) {
    if (tracks.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = trackColumnWidth(maxWidth)
        val scrollState = rememberLazyListState()
        // Bounded to 20 tracks; do not compare a potentially huge Library list
        // on each recomposition just to render its first five columns.
        val columns = nativeArtistTrackColumns(tracks)
        val columnKey: ((List<T>) -> Any)? = itemKey?.let { key ->
            { column: List<T> -> key(column.first()) }
        }
        LazyRow(
            state = scrollState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = scrollState),
        ) {
            items(columns, key = columnKey) { column ->
                Column(Modifier.width(columnWidth)) {
                    column.forEach { track -> row(track) }
                }
            }
        }
    }
}

/**
 * Album/single shelves use one native horizontal layout. The caller still
 * owns individual cards and their menus, pin, rating and selection actions.
 * Library can show every verified release; online shelves retain their own
 * existing 'Show all' presentation limit.
 */
@Composable
internal fun <T> NativeArtistReleaseCarousel(
    releases: List<T>,
    horizontalPadding: Dp = PAGE_GUTTER,
    itemSpacing: Dp = 14.dp,
    itemKey: ((T) -> Any)? = null,
    card: @Composable (T) -> Unit,
) {
    if (releases.isEmpty()) return
    val scrollState = rememberLazyListState()
    LazyRow(
        state = scrollState,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = scrollState),
    ) {
        items(releases, key = itemKey) { release -> card(release) }
    }
}
