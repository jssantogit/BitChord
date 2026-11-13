package com.music.bitchord.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Box
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

/** Same toolbar layout as canonical native playlists; only source-authorized actions differ. */
@Composable
internal fun CanonicalPublicPlaylistToolbarMenus(
    playlist: ListenBrainzPublicPlaylist,
    sortOpen: Boolean,
    optionsOpen: Boolean,
    onSortDismiss: () -> Unit,
    onOptionsDismiss: () -> Unit,
    sort: PlaylistLibrarySort,
    onSort: (PlaylistLibrarySort) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { LibraryPlaylistPinRepository.get(context.applicationContext) }
    val pins by repository.pins.collectAsStateWithLifecycle()
    val pin = remember(playlist.mbid, playlist.title, playlist.creator) {
        LibraryPlaylistPin("public:" + playlist.mbid, playlist.title, playlist.creator)
    }
    val pinned = pins.any { it.id == pin.id }
    val ratingTarget = remember(playlist.mbid) { canonicalPublicPlaylistRatingTarget(playlist.mbid) }
    var editing by remember(playlist.mbid) { mutableStateOf(false) }
    if (editing && ratingTarget != null) {
        CanonicalCatalogEntityRatingDialog(
            target = ratingTarget, title = "Avaliar playlist",
            onDismiss = { editing = false },
        )
    }
    Box {
        DropdownMenu(expanded = sortOpen, onDismissRequest = onSortDismiss) {
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
                    onClick = { onSort(option); onSortDismiss() },
                )
            }
        }
        DropdownMenu(expanded = optionsOpen, onDismissRequest = onOptionsDismiss) {
            if (ratingTarget != null) {
                DropdownMenuItem(text = { Text("Avaliar playlist") }, onClick = {
                    onOptionsDismiss()
                    editing = true
                })
            }
            DropdownMenuItem(
                text = { Text(if (pinned) "Desafixar da Biblioteca" else "Fixar na Biblioteca") },
                enabled = validLibraryPlaylistPin(pin),
                onClick = { repository.toggle(pin); onOptionsDismiss() },
            )
            DropdownMenuItem(text = { Text("Compartilhar playlist") }, onClick = {
                onOptionsDismiss()
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, playlist.title + " — " + playlist.creator)
                }
                context.startActivity(Intent.createChooser(share, "Compartilhar playlist"))
            })
        }
    }
}
