package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.music.bitchord.ui.theme.ArtworkPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * One source-neutral playlist viewport, regardless of legacy online,
 * MediaStore-local or canonical Navidrome/Library membership.
 *
 * Adapters supply track identity, artwork, playback actions and optional
 * suggestions; they cannot introduce another page shell or scroll geometry.
 */
@Composable
internal fun NativePlaylistDetailFrame(
    palette: ArtworkPalette,
    listState: LazyListState,
    bottomPadding: Dp,
    /** Native long-press actions draw over the same page, not in a detached
     * Popup with a separate opaque material or a cloned track bitmap. */
    heldActions: @Composable (HazeState) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val hazeState = remember { HazeState() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .hazeSource(hazeState)
                .testTag("native-playlist-detail-frame"),
            contentPadding = PaddingValues(bottom = bottomPadding),
            content = content,
        )
        heldActions(hazeState)
    }
}

/**
 * One native playlist header irrespective of whether membership originates
 * in the unified Library, a device folder, or ListenBrainz public discovery.
 * Provider adapters supply only data and available actions; no second page
 * layout or source-specific toolbar should be introduced.
 */
@Composable
internal fun NativePlaylistDetailHeader(
    title: String,
    credit: String,
    meta: String,
    palette: ArtworkPalette,
    sleeveSize: Dp,
    artwork: @Composable BoxScope.() -> Unit,
    actions: @Composable () -> Unit,
    rating: Double? = null,
    onRate: (() -> Unit)? = null,
) {
    NativeReleaseHeaderLayout(
        title = title,
        credit = credit,
        meta = meta,
        palette = palette,
        rating = rating,
        onRate = onRate,
        sleeveSize = sleeveSize,
        artwork = artwork,
        actions = actions,
    )
}
