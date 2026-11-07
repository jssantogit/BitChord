package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistDetail
import com.music.bitchord.data.model.UiState

/**
 * Thin route adapter; the original native playlist detail frame owns the
 * layout. Keep extra Compose groups OUTSIDE the 64KB BitChordApp method,
 * which the Android and TV Gradle gates compile separately.
 */
@Composable
internal fun CanonicalPublicPlaylistRoute(
    routeKey: String,
    selected: ListenBrainzPublicPlaylist?,
    state: UiState<ListenBrainzPublicPlaylistDetail>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRetry: (ListenBrainzPublicPlaylist) -> Unit,
    onVerifiedRecordingPlay: (String) -> Unit,
) {
    var held by remember(routeKey) { mutableStateOf(selected) }
    if (selected != null && routeKey == "public_playlist:${selected.mbid}") {
        held = selected
    }
    held?.let { playlist ->
        CanonicalPublicPlaylistDetailScreen(
            playlist = playlist,
            state = if (selected?.mbid == playlist.mbid) state else UiState.Loading,
            listState = listState,
            contentPadding = contentPadding,
            onBack = onBack,
            onRetry = { onRetry(playlist) },
            onVerifiedRecordingPlay = onVerifiedRecordingPlay,
        )
    }
}
