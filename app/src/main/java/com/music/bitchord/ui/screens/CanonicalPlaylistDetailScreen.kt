package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.AccentRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved playlist opens for listening before it opens for editing.
 * Canonical recordings are resolved from the saved RecordingIds, never by title
 * or by fabricating legacy Song/videoIds for DetailScreen.
 */
@Composable
internal fun CanonicalPlaylistDetailScreen(
    card: LibraryPlaylistCard,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onAction: (CanonicalCollectionIntent) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val rows by produceState<List<V2DisplayRow>?>(initialValue = null, key1 = card.id) {
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
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        Text("Voltar")
                    }
                    TextButton(onClick = onEdit) { Text("Editar", color = AccentRed) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val first = rows?.firstOrNull()
                            if (first != null) {
                                SmartPlaylistPreviewArtwork(
                                    row = first,
                                    modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally),
                                )
                            } else {
                                androidx.compose.foundation.layout.Box(
                                    Modifier.fillMaxWidth(), contentAlignment = Alignment.Center,
                                ) { PlaylistWizardArtworkPlaceholder(180.dp) }
                            }
                            Text(card.title, style = MaterialTheme.typography.headlineLarge)
                            Text(
                                when (card) {
                                    is LibraryPlaylistCard.Smart -> "Playlist inteligente"
                                    else -> "Playlist normal"
                                } + " · " + (rows?.size?.let { "$it músicas" } ?: "Carregando músicas…"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(
                                    onClick = { onAction(CanonicalCollectionIntent.PLAY_NOW) },
                                    enabled = rows?.isNotEmpty() == true &&
                                        (rows?.size ?: 0) <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE,
                                ) { Text("Reproduzir", color = AccentRed) }
                                TextButton(
                                    onClick = { onAction(CanonicalCollectionIntent.ADD_TO_QUEUE) },
                                    enabled = rows?.isNotEmpty() == true &&
                                        (rows?.size ?: 0) <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE,
                                ) { Text("Adicionar à fila") }
                            }
                        }
                    }
                    if (rows?.isEmpty() == true) {
                        item {
                            Text(
                                "Esta playlist está vazia ou não foi possível consultar as fontes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(rows.orEmpty(), key = { it.id }) { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SmartPlaylistPreviewArtwork(track, Modifier.size(52.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
