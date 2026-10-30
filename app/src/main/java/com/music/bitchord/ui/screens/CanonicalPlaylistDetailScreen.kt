package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.AccentRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork always comes from recordings actually in the saved collection.
 * No generated cover URLs, no legacy video IDs and no title-based album lookup.
 */
@Composable
internal fun CanonicalPlaylistCoverArt(
    rows: List<V2DisplayRow>,
    modifier: Modifier = Modifier,
) {
    when {
        rows.size >= 4 -> Column(modifier.clip(RoundedCornerShape(12.dp))) {
            for (offset in listOf(0, 2)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (index in offset until offset + 2) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            SmartPlaylistPreviewArtwork(
                                row = rows[index], modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        rows.isNotEmpty() -> SmartPlaylistPreviewArtwork(
            row = rows.first(), modifier = modifier,
        )
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            PlaylistWizardArtworkPlaceholder(180.dp)
        }
    }
}

/** The same native Library row used everywhere, wired to canonical playback. */
@Composable
internal fun CanonicalPlaylistTrackRow(
    track: V2DisplayRow,
    onPlay: (String) -> Unit,
    onQueue: ((String, CanonicalCollectionIntent) -> Unit)? = null,
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    LibraryRow(
        title = track.title,
        subtitle = track.artist,
        imageUrl = null,
        onClick = { onPlay(track.id) },
        modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        horizontalPadding = 0.dp,
        artwork = {
            SmartPlaylistPreviewArtwork(
                row = track, modifier = Modifier.fillMaxSize(),
            )
        },
        trailing = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Ações da música")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproduzir daqui") },
                        onClick = {
                            menuExpanded = false
                            onPlay(track.id)
                        },
                    )
                    if (onQueue != null) {
                        DropdownMenuItem(
                            text = { Text("Reproduzir a seguir") },
                            onClick = {
                                menuExpanded = false
                                onQueue(track.id, CanonicalCollectionIntent.ADD_NEXT)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Adicionar à fila") },
                            onClick = {
                                menuExpanded = false
                                onQueue(track.id, CanonicalCollectionIntent.ADD_TO_QUEUE)
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * A playlist is a native Library detail page, not a separate full-screen Dialog.
 * The owning Library host keeps the floating mini-player and bottom navigation.
 */
@Composable
internal fun CanonicalPlaylistDetailScreen(
    card: LibraryPlaylistCard,
    onClose: () -> Unit,
    onAction: (CanonicalCollectionIntent) -> Unit,
    onTrackClick: (String) -> Unit,
    onTrackQueue: (String, CanonicalCollectionIntent) -> Unit,
    contentPadding: PaddingValues,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current.applicationContext
    val rows by produceState<List<V2DisplayRow>?>(initialValue = null, key1 = card.id, key2 = card) {
        value = try {
            when (card) {
                is LibraryPlaylistCard.CanonicalNormal ->
                    canonicalNormalPlaylistSelectedRows(
                        context, card.definition.recordingIds, card.definition.sourceProviderKey,
                    )
                is LibraryPlaylistCard.Smart -> {
                    val evaluator = persistentSmartPlaylistPreview(context)
                    val ids = withContext(Dispatchers.IO) {
                        evaluator.preview(card.definition.query, card.definition.id)
                            .map { it.recordingId }
                    }
                    canonicalNormalPlaylistSelectedRows(context, ids)
                }
                is LibraryPlaylistCard.Normal -> emptyList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    val canPlay = rows?.isNotEmpty() == true &&
        (rows?.size ?: 0) <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE &&
        rows.orEmpty().none { it.artist == "Fonte não disponível" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "playlist-detail-cover") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CanonicalPlaylistCoverArt(
                    rows = rows.orEmpty(), modifier = Modifier.size(252.dp),
                )
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = (if (card is LibraryPlaylistCard.Smart) "Playlist inteligente" else
                        "Playlist normal") + " · " +
                        (rows?.size?.let { it.toString() + " músicas" } ?: "Carregando músicas…"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { onAction(CanonicalCollectionIntent.PLAY_NOW) },
                        enabled = canPlay,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text("Tocar")
                    }
                    FilledTonalButton(
                        onClick = { onAction(CanonicalCollectionIntent.ADD_TO_QUEUE) },
                        enabled = canPlay,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null)
                        Text("Fila")
                    }
                }
            }
        }
        if (rows?.isEmpty() == true) {
            item(key = "playlist-detail-empty") {
                Text(
                    "Esta playlist está vazia ou não foi possível consultar as fontes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                )
            }
        }
        items(rows.orEmpty(), key = { it.id }) { track ->
            CanonicalPlaylistTrackRow(
                track = track,
                onPlay = onTrackClick,
                onQueue = onTrackQueue,
            )
        }
    }
}
