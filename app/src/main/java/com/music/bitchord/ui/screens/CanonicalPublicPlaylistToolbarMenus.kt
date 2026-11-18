package com.music.bitchord.ui.screens

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist

/** Sort controls are anchored to the sort button, not the overflow button. */
@Composable
internal fun CanonicalPublicPlaylistSortMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sort: PlaylistLibrarySort,
    onSort: (PlaylistLibrarySort) -> Unit,
) {
    com.music.bitchord.ui.components.NativeCompactOverflowMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        listOf(
            PlaylistLibrarySort.DEFAULT to "Ordem original",
            PlaylistLibrarySort.TITLE_ASC to "Título (A–Z)",
            PlaylistLibrarySort.TITLE_DESC to "Título (Z–A)",
        ).forEach { (option, title) ->
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = title,
                selected = sort == option,
                onClick = {
                    onSort(option)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Context actions are anchored to their actual ⋯ button by the caller.
 * Editing stays mounted after dismissal so the rating dialog can open.
 */
@Composable
internal fun CanonicalPublicPlaylistOverflowMenu(
    playlist: ListenBrainzPublicPlaylist,
    expanded: Boolean,
    onDismiss: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState,
    anchorBounds: androidx.compose.ui.unit.IntRect? = null,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) {
        LibraryPlaylistPinRepository.get(context.applicationContext)
    }
    val pins by repository.pins.collectAsStateWithLifecycle()
    val pin = remember(playlist.mbid, playlist.title, playlist.creator) {
        LibraryPlaylistPin("public:" + playlist.mbid, playlist.title, playlist.creator)
    }
    val pinned = pins.any { it.id == pin.id }
    val ratingTarget = remember(playlist.mbid) {
        canonicalPublicPlaylistRatingTarget(playlist.mbid)
    }
    var editing by remember(playlist.mbid) { mutableStateOf(false) }
    if (editing && ratingTarget != null) {
        CanonicalCatalogEntityRatingDialog(
            target = ratingTarget, title = "Avaliar playlist",
            onDismiss = { editing = false },
        )
    }
    if (expanded) {
        com.music.bitchord.FrostedMenuPanel(
            hazeState = hazeState,
            anchorBounds = anchorBounds,
            onDismiss = onDismiss,
        ) {
            if (ratingTarget != null) {
                com.music.bitchord.FrostedMenuRow(
                    label = "Avaliar playlist",
                    checked = false,
                    icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.RatingPlain,
                    onClick = {
                        onDismiss()
                        editing = true
                    },
                )
            }
            com.music.bitchord.FrostedMenuRow(
                label = if (pinned) "Desafixar da Biblioteca" else "Fixar na Biblioteca",
                checked = false,
                icon = com.music.bitchord.ui.icons.BitChordIcons.Pin,
                onClick = { 
                    if (validLibraryPlaylistPin(pin)) {
                        repository.toggle(pin)
                        onDismiss()
                    }
                },
            )
            com.music.bitchord.FrostedMenuRow(
                label = "Compartilhar playlist",
                checked = false,
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.Share,
                onClick = {
                    onDismiss()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, playlist.title + " — " + playlist.creator)
                    }
                    context.startActivity(Intent.createChooser(share, "Compartilhar playlist"))
                },
            )
            com.music.bitchord.FrostedMenuRow(
                label = "Configurações",
                checked = false,
                icon = androidx.compose.material.icons.Icons.Rounded.Settings,
                onClick = {
                    onDismiss()
                    onSettings()
                },
            )
        }
    }
}
