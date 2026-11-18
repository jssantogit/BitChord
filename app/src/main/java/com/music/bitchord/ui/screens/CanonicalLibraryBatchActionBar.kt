package com.music.bitchord.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.NativeContextActionPlate
import com.music.bitchord.ui.components.NativeActionCardTreatment
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.components.ActionRowStyle
import com.music.bitchord.ui.components.LocalActionRowStyle

/** The action list grows up from a stable summary dock, with no size spring. */
internal fun selectionActionsEnterTransition(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) EnterTransition.None else
        expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(150)) +
            fadeIn(tween(150))

internal fun selectionActionsExitTransition(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) ExitTransition.None else
        shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(150)) +
            fadeOut(tween(150))

/**
 * Native multi-selection dock: a small, opaque, neutral plate. It starts
 * COLLAPSED and never samples the underlying selectable rows as an action
 * background. Opening actions must not move the dock against the bottom bar.
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
    hazeState: dev.chrisbanes.haze.HazeState? = null,
) {
    if (selectedEntityCount < 1) return
    var expanded by remember { mutableStateOf(false) }
    val reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current
    NativeContextActionPlate(
        modifier = modifier.widthIn(max = 340.dp).testTag("native-selection-held-card"),
        // Selection is not a frosted filter: Haze on moving selectable rows
        // caused the overlapping text and colors visible in the physical smoke.
        treatment = NativeActionCardTreatment.SOLID,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Put actions ABOVE the summary to expand toward the page, never
            // down through the navigation capsule or the miniplayer.
            AnimatedVisibility(
                visible = expanded,
                enter = selectionActionsEnterTransition(reduceMotion),
                exit = selectionActionsExitTransition(reduceMotion),
            ) {
                CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("native-selection-expanded-actions"),
                    ) {
                        ActionRow(
                            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            label = "Adicionar seleção à playlist",
                            onClick = onAddToPlaylist,
                        )
                        ActionRow(
                            icon = Icons.Rounded.SkipNext,
                            label = "Tocar a seleção a seguir",
                            onClick = onPlayNext,
                        )
                        ActionRow(
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            label = "Adicionar seleção à fila",
                            onClick = onAddToQueue,
                        )
                        onRateSelection?.let { rate ->
                            ActionRow(
                                icon = Icons.Rounded.Star,
                                label = "Avaliar seleção",
                                onClick = rate,
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().testTag("native-selection-summary-dock"),
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
        }
    }
}
