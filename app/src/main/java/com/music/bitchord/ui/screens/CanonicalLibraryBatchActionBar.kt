package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.music.bitchord.ui.components.NativeContextActionPlate
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    selectedEntityCount: Int,
    summary: String,
    onClear: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRateSelection: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (selectedEntityCount < 1) return
    var expanded by remember { mutableStateOf(true) }
    // Compact phone-safe dock: the count is never squeezed between four
    // 48dp controls on one line. Both rows use the existing wizard actions
    // inside the same lifted plate as held-item menus, not a second Settings sheet.
    NativeContextActionPlate(
        modifier = modifier.widthIn(max = 340.dp).testTag("native-selection-held-card"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaylistWizardCompactAction(
                    label = "Encerrar seleção",
                    icon = Icons.Rounded.Close,
                    onClick = onClear,
                )
                Text(
                    summary,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = summary
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PlaylistWizardCompactAction(
                    label = if (expanded) "Recolher ações" else "Expandir ações",
                    icon = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    onClick = { expanded = !expanded },
                )
            }
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistWizardCompactAction(
                        label = "Adicionar seleção à playlist",
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        onClick = onAddToPlaylist,
                    )
                    PlaylistWizardCompactAction(
                        label = "Tocar a seleção a seguir",
                        icon = Icons.Rounded.SkipNext,
                        onClick = onPlayNext,
                    )
                    PlaylistWizardCompactAction(
                        label = "Adicionar seleção à fila",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        onClick = onAddToQueue,
                    )
                    onRateSelection?.let { rate ->
                        PlaylistWizardCompactAction(
                            label = "Avaliar seleção",
                            icon = Icons.Rounded.Star,
                            onClick = rate,
                        )
                    }
                }
            }
        }
    }
}
