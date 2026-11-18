package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import com.music.bitchord.ui.components.NativeCompactMenuItem
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * Recent additions are verified local RecordingIds, not synthetic media.
 * Share native library actions with the held track card; never offer
 * misleading download of a file that is already on the device.
 */
@Composable
internal fun CanonicalRecentHeldActions(
    isPinned: Boolean,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditRating: () -> Unit,
    onTogglePin: () -> Unit,
) {
    NativeCompactMenuItem(
        label = "Tocar a seguir",
        icon = Icons.Rounded.SkipNext,
        onClick = onPlayNext,
    )
    NativeCompactMenuItem(
        label = "Adicionar à fila",
        icon = Icons.Rounded.QueueMusic,
        onClick = onQueue,
    )
    NativeCompactMenuItem(
        label = "Adicionar à playlist",
        icon = Icons.Rounded.PlaylistAdd,
        onClick = onAddToPlaylist,
    )
    NativeCompactMenuItem(
        label = "Editar avaliação",
        icon = Icons.Rounded.StarOutline,
        onClick = onEditRating,
    )
    NativeCompactMenuItem(
        label = if (isPinned) "Desafixar da Biblioteca" else "Fixar na Biblioteca",
        icon = BitChordIcons.Pin,
        onClick = onTogglePin,
    )
}
