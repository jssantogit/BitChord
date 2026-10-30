package com.music.bitchord.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
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
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.theme.AccentRed
import com.music.bitchord.ui.components.CoverPlaceholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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


/** The playlist collection shares the Library host's search and sort, not a second page shell. */
internal enum class PlaylistLibrarySort { DEFAULT, TITLE_ASC, TITLE_DESC }
internal enum class PlaylistLibraryTypeFilter { ALL, NORMAL, SMART }

internal fun visibleLibraryPlaylistCards(
    cards: List<LibraryPlaylistCard>,
    query: String,
    filter: PlaylistLibraryTypeFilter,
    sort: PlaylistLibrarySort,
): List<LibraryPlaylistCard> {
    val filtered = cards.filter { card ->
        card.title.contains(query.trim(), ignoreCase = true) &&
            when (filter) {
                PlaylistLibraryTypeFilter.ALL -> true
                PlaylistLibraryTypeFilter.NORMAL -> card !is LibraryPlaylistCard.Smart
                PlaylistLibraryTypeFilter.SMART -> card is LibraryPlaylistCard.Smart
            }
    }
    return when (sort) {
        PlaylistLibrarySort.DEFAULT -> filtered
        PlaylistLibrarySort.TITLE_ASC -> filtered.sortedWith(
            compareBy<LibraryPlaylistCard> { it.title.lowercase() }.thenBy { it.id },
        )
        PlaylistLibrarySort.TITLE_DESC -> filtered.sortedWith(
            compareByDescending<LibraryPlaylistCard> { it.title.lowercase() }.thenBy { it.id },
        )
    }
}

/** Same PillTextField used by Settings and creation: no unrelated Material text field. */
@Composable
internal fun PlaylistLibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    PillTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Buscar em Playlists",
        accessibilityLabel = "Buscar em Playlists",
        modifier = Modifier.fillMaxWidth(),
    )
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
    onSmartTrackAction: (String, String, CanonicalCollectionIntent, Boolean) -> Unit,
    onCanonicalNormalTrackAction: (String, String, CanonicalCollectionIntent, Boolean) -> Unit,
    contentPadding: PaddingValues,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onResetFilters: () -> Unit = {},
    sort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
    filter: PlaylistLibraryTypeFilter = PlaylistLibraryTypeFilter.ALL,
    selectedCard: LibraryPlaylistCard? = null,
    onSelectedCardChange: (LibraryPlaylistCard?) -> Unit = {},
    listState: LazyListState? = null,
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
    var smartLoaded by remember { mutableStateOf(false) }
    var normalLoaded by remember { mutableStateOf(false) }
    val canonicalNormal by produceState(
        initialValue = emptyList<SavedCanonicalStaticPlaylist>(), key1 = normalRepo,
    ) {
        try {
            normalRepo.observe().collect {
                value = it
                normalFailed = false
                normalLoaded = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { normalFailed = true; normalLoaded = true }
    }
    val smart by produceState(initialValue = emptyList<SavedSmartPlaylistV2Definition>(), key1 = repo) {
        try {
            repo.observe().collect {
                value = it
                failed = false
                smartLoaded = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { failed = true; smartLoaded = true }
    }
    // Stable IDs make editing, queueing and ordering independent of display
    // names; only locally saved normal playlists are included in this slot.
    val cards = remember(smart, canonicalNormal, normalPlaylists) {
        smart.map { LibraryPlaylistCard.Smart(it) } +
            canonicalNormal.map { LibraryPlaylistCard.CanonicalNormal(it) } +
            normalPlaylists.filter { it.browseId?.startsWith("local:playlist:") == true }
                .map { LibraryPlaylistCard.Normal(it) }
    }
    val openedCard = selectedCard
    val visible = visibleLibraryPlaylistCards(cards, query, filter, sort)
    val playlistScroll = listState ?: rememberLazyListState()

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

            }
        }
        item(key = "playlist-search") {
            Box(Modifier.fillMaxWidth().padding(
                horizontal = PAGE_GUTTER, vertical = 2.dp,
            )) {
                PlaylistLibrarySearchField(query, onQueryChange)
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
        if (!normalLoaded || !smartLoaded) item(key = "playlist-loading") {
            Box(
                Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        if (normalLoaded && smartLoaded && visible.isEmpty()) item(key = "playlist-empty") {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 380.dp)
                    .padding(horizontal = PAGE_GUTTER),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val trulyEmpty = cards.isEmpty()
                    Text(
                        if (trulyEmpty) "Nenhuma Playlist" else "Nenhuma playlist encontrada",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        when {
                            !trulyEmpty -> "Experimente outra busca ou altere o filtro."
                            else -> "Crie playlists com as músicas da sua biblioteca."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Box(Modifier.padding(top = 14.dp)) {
                        PlaylistWizardPrimaryAction(
                            label = if (trulyEmpty) "Nova Playlist" else "Limpar busca e filtros",
                            onClick = if (trulyEmpty) onNewSmartPlaylist else onResetFilters,
                        )
                    }
                }
            }
        }
        items(visible, key = { it.id }) { card ->
            val openCard: () -> Unit = {
                when (card) {
                    is LibraryPlaylistCard.Smart,
                    is LibraryPlaylistCard.CanonicalNormal -> onSelectedCardChange(card)
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
            onClose = { onSelectedCardChange(null) },
            onAction = { intent ->
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onSmartAction(selected.definition.id, intent)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onCanonicalNormalAction(selected.definition.id, intent)
                    is LibraryPlaylistCard.Normal -> Unit
                }
            },
            onTrackClick = { recordingId ->
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onSmartTrackAction(
                            selected.definition.id, recordingId,
                            CanonicalCollectionIntent.PLAY_NOW, true,
                        )
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onCanonicalNormalTrackAction(
                            selected.definition.id, recordingId,
                            CanonicalCollectionIntent.PLAY_NOW, true,
                        )
                    is LibraryPlaylistCard.Normal -> Unit
                }
            },
            onTrackQueue = { recordingId, intent ->
                when (selected) {
                    is LibraryPlaylistCard.Smart ->
                        onSmartTrackAction(selected.definition.id, recordingId, intent, false)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onCanonicalNormalTrackAction(
                            selected.definition.id, recordingId, intent, false,
                        )
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
        initialValue = emptyList(), key1 = card.id, key2 = card,
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
            is LibraryPlaylistCard.Smart -> {
                try {
                    val ids = withContext(Dispatchers.IO) {
                        // Building the rule evaluator can read Room configuration.
                        // Keep it away from the main thread, especially in a lazy shelf.
                        persistentSmartPlaylistPreview(context)
                            .preview(card.definition.query, card.definition.id)
                            .take(4).map { it.recordingId }
                    }
                    canonicalNormalPlaylistSelectedRows(context, ids)
                        .filterNot { it.title == "Gravação indisponível" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
            is LibraryPlaylistCard.Normal -> emptyList()
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
            is LibraryPlaylistCard.Normal -> card.shelfItem.subtitle.ifBlank { "Playlist normal" }
        },
        imageUrl = (card as? LibraryPlaylistCard.Normal)?.shelfItem?.thumbnailUrl,
        onClick = onClick,
        modifier = modifier,
        horizontalPadding = 4.dp,
        artworkSize = 64.dp,
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
