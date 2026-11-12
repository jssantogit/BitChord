package com.music.bitchord.ui.screens

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            PlaylistLibrarySort.DEFAULT to "Ordem original",
            PlaylistLibrarySort.TITLE_ASC to "Título (A–Z)",
            PlaylistLibrarySort.TITLE_DESC to "Título (Z–A)",
        ).forEach { (option, title) ->
            DropdownMenuItem(
                text = { Text(title) },
                trailingIcon = if (sort == option) {
                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                } else null,
                onClick = { onSort(option); onDismiss() },
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
    com.music.bitchord.ui.components.NativeCompactOverflowMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (ratingTarget != null) {
            com.music.bitchord.ui.components.NativeCompactMenuItem(
                label = "Avaliar playlist",
                icon = com.music.bitchord.ui.icons.PlaylistEditorIcons.RatingPlain,
                onClick = {
                    onDismiss()
                    editing = true
                },
            )
        }
        com.music.bitchord.ui.components.NativeCompactMenuItem(
            label = if (pinned) "Desafixar da Biblioteca" else "Fixar na Biblioteca",
            icon = com.music.bitchord.ui.icons.BitChordIcons.Pin,
            enabled = validLibraryPlaylistPin(pin),
            onClick = { repository.toggle(pin); onDismiss() },
        )
        com.music.bitchord.ui.components.NativeCompactMenuItem(
            label = "Compartilhar playlist",
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
    }
}
