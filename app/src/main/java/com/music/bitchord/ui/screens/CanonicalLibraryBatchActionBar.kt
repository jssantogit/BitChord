package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Native small contextual selection strip. Composed entirely out of the
 * Settings group's existing shape/surface role and PlaylistWizardCompactAction
 * icon buttons; no separate generic Material3 bar or copied Symfonium design.
 *
 * The Library host positions this above the app's native floating bottom bar.
 */
@Composable
internal fun CanonicalLibraryBatchActionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedCount < 1) return
    Row(
        modifier = modifier.clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlaylistWizardCompactAction(
            label = "Encerrar seleção",
            icon = Icons.Rounded.Close,
            onClick = onClear,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            "$selectedCount",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(2.dp))
        PlaylistWizardCompactAction(
            label = "Adicionar seleção à playlist",
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            onClick = onAddToPlaylist,
        )
        PlaylistWizardCompactAction(
            label = "Tocar a seleção a seguir",
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            onClick = onPlayNext,
        )
    }
}
