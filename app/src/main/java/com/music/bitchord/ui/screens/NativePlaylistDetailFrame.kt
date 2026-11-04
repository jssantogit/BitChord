package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.music.bitchord.ui.theme.ArtworkPalette

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
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("native-playlist-detail-frame"),
        contentPadding = PaddingValues(bottom = bottomPadding),
        content = content,
    )
}
