package com.music.bitchord.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.AccentRed
import com.music.bitchord.ui.components.CoverPlaceholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * First-party playlist surface in the Library itself. V2 definitions and
 * locally persisted normal playlists are two independent storage systems;
 * never manufacture a video/browse ID for a V2 recording collection.
 */
internal sealed interface LibraryPlaylistCard {
    val id: String
    val title: String
    data class Smart(
        val definition: SavedSmartPlaylistV2Definition,
    ) : LibraryPlaylistCard {
        override val id: String get() = "v2:" + definition.id
        override val title: String get() = definition.name
    }
    data class CanonicalNormal(val definition: SavedCanonicalStaticPlaylist) : LibraryPlaylistCard {
        override val id: String get() = "normal:" + definition.id
        override val title: String get() = definition.name
    }
    data class Normal(val shelfItem: ShelfItem) : LibraryPlaylistCard {
        override val id: String get() = "local:" + shelfItem.browseId.orEmpty()
        override val title: String get() = shelfItem.title
    }
}

@Composable
internal fun UnifiedPlaylistLibraryShelf(
    normalPlaylists: List<ShelfItem>,
    onNewSmartPlaylist: () -> Unit,
    onOpenSmartPlaylist: (String) -> Unit,
    onOpenCanonicalNormalPlaylist: (String) -> Unit,
    onOpenNormalPlaylist: (ShelfItem) -> Unit,
    onSmartAction: (String, CanonicalCollectionIntent) -> Unit,
    onCanonicalNormalAction: (String, CanonicalCollectionIntent) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) {
        SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
    }
    val normalRepo = remember(context) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
    }
    var failed by remember { mutableStateOf(false) }
    var normalFailed by remember { mutableStateOf(false) }
    val canonicalNormal by produceState(
        initialValue = emptyList<SavedCanonicalStaticPlaylist>(), key1 = normalRepo,
    ) {
        try {
            normalRepo.observe().collect {
                value = it
                normalFailed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { normalFailed = true }
    }
    val smart by produceState(initialValue = emptyList<SavedSmartPlaylistV2Definition>(), key1 = repo) {
        try {
            repo.observe().collect {
                value = it
                failed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { failed = true }
    }
    // Stable IDs make editing, queueing and ordering independent of display
    // names; only locally saved normal playlists are included in this slot.
    val cards = remember(smart, canonicalNormal, normalPlaylists) {
        smart.map { LibraryPlaylistCard.Smart(it) } +
            canonicalNormal.map { LibraryPlaylistCard.CanonicalNormal(it) } +
            normalPlaylists.filter { it.browseId?.startsWith("local:playlist:") == true }
                .map { LibraryPlaylistCard.Normal(it) }
    }
    var openedCard by remember { mutableStateOf<LibraryPlaylistCard?>(null) }
    val playlistScroll = rememberLazyListState()

    // The host owns status-bar and floating-player insets. Applying a second
    // statusBarsPadding() + 64dp here created an artificial gap and a second
    // page chrome inside Library. Use the same measured padding as LibraryScreen.
    if (openedCard == null) LazyColumn(
        state = playlistScroll,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "playlist-header") {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Playlists",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onNewSmartPlaylist,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("+ Nova", color = AccentRed) }
            }
        }
        if (failed) item(key = "smart-error") {
            Text(
                "Não foi possível carregar playlists inteligentes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
        if (normalFailed) item(key = "normal-error") {
            Text(
                "Não foi possível carregar playlists normais canônicas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
        if (cards.isEmpty()) item(key = "playlist-empty") {
            Text(
                "Suas playlists aparecerão aqui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
        items(cards, key = { it.id }) { card ->
            val openCard: () -> Unit = {
                when (card) {
                    is LibraryPlaylistCard.Smart,
                    is LibraryPlaylistCard.CanonicalNormal -> openedCard = card
                    is LibraryPlaylistCard.Normal -> onOpenNormalPlaylist(card.shelfItem)
                }
            }
            UnifiedPlaylistCompactRow(
                card = card,
                onClick = openCard,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
    }
    openedCard?.let { selected ->
        CanonicalPlaylistDetailScreen(
            card = selected,
            onClose = { openedCard = null },
            onEdit = {
                openedCard = null
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onOpenSmartPlaylist(selected.definition.id)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onOpenCanonicalNormalPlaylist(selected.definition.id)
                    is LibraryPlaylistCard.Normal ->
                        onOpenNormalPlaylist(selected.shelfItem)
                }
            },
            onAction = { intent ->
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onSmartAction(selected.definition.id, intent)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onCanonicalNormalAction(selected.definition.id, intent)
                    is LibraryPlaylistCard.Normal -> Unit
                }
            },
            contentPadding = contentPadding,
        )
    }
}

/** Compact vertical Library presentation; the same actions work in the grid. */
@Composable
private fun UnifiedPlaylistCompactRow(
    card: LibraryPlaylistCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val canonicalCover by produceState<List<V2DisplayRow>>(
        initialValue = emptyList(), key1 = card.id,
    ) {
        value = when (card) {
            is LibraryPlaylistCard.CanonicalNormal -> {
                try {
                    canonicalNormalPlaylistSelectedRows(
                        context, card.definition.recordingIds.take(4),
                        card.definition.sourceProviderKey,
                    ).filterNot { it.title == "Gravação indisponível" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }
    // Reuse the existing Spotify/Library row rather than maintaining a
    // second cover/title/subtitle/touch-target implementation.
    LibraryRow(
        title = card.title,
        subtitle = when (card) {
            is LibraryPlaylistCard.Smart -> "Playlist inteligente"
            is LibraryPlaylistCard.CanonicalNormal ->
                "${card.definition.recordingIds.size} músicas · Normal"
            is LibraryPlaylistCard.Normal -> "Playlist normal"
        },
        imageUrl = (card as? LibraryPlaylistCard.Normal)?.shelfItem?.thumbnailUrl,
        onClick = onClick,
        modifier = modifier,
        horizontalPadding = 4.dp,
        artworkSize = 76.dp,
        artwork = when {
            canonicalCover.isNotEmpty() -> ({
                CanonicalPlaylistCoverArt(canonicalCover, Modifier.fillMaxSize())
            })
            card is LibraryPlaylistCard.Smart -> ({
                CoverPlaceholder(
                    icon = Icons.Rounded.AutoAwesome,
                    modifier = Modifier.fillMaxSize(),
                )
            })
            else -> null
        },
    )
}
