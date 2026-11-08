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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.RowMoreButton
import kotlinx.coroutines.launch
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.themeArtworkPalette
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
/** The empty screen already owns a primary create action. */
internal fun showPlaylistHeaderCreateAction(playlistCount: Int): Boolean =
    playlistCount > 0

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

/** The canonical queue planner can execute these two verified RecordingId collections. */
internal fun canQueueLibraryPlaylist(card: LibraryPlaylistCard): Boolean =
    card is LibraryPlaylistCard.Smart || card is LibraryPlaylistCard.CanonicalNormal

/** All three first-party local playlist stores support non-destructive delete. */
internal fun canDeleteLibraryPlaylist(card: LibraryPlaylistCard): Boolean = true

/** Stable card IDs, fresh saved definitions; never keep a stale UI snapshot after editing. */
internal fun latestLibraryPlaylistCard(
    selected: LibraryPlaylistCard,
    current: List<LibraryPlaylistCard>,
): LibraryPlaylistCard? = current.firstOrNull { it.id == selected.id }

/** Use the same 46dp glass field as the native music/playlist detail page. */
@Composable
internal fun PlaylistLibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit = {},
    autoFocus: Boolean = false,
) {
    NativeDetailSearchField(
        query = query,
        onQueryChange = onQueryChange,
        onClose = onClose,
        autoFocus = autoFocus,
        onFocused = {},
        palette = themeArtworkPalette(),
        placeholder = "Buscar em Playlists",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedPlaylistLibraryShelf(
    normalPlaylists: List<ShelfItem>,
    onNewSmartPlaylist: () -> Unit,
    onOpenSmartPlaylist: (String) -> Unit,
    onOpenCanonicalNormalPlaylist: (String) -> Unit,
    onAddSongsToCanonicalNormalPlaylist: (String) -> Unit = onOpenCanonicalNormalPlaylist,
    onLegacyPlay: (String, List<Song>, Int) -> Unit,
    onLegacyShuffle: (String, List<Song>) -> Unit,
    onLegacyQueue: (List<Song>) -> Unit,
    onLegacyNext: (List<Song>) -> Unit,
    onLegacySongMenu: (Song) -> Unit,
    onLegacySongSwipe: (Song) -> Unit,
    onSmartAction: (String, CanonicalCollectionIntent) -> Unit,
    onCanonicalNormalAction: (String, CanonicalCollectionIntent) -> Unit,
    onSmartShuffle: (String) -> Unit,
    onCanonicalNormalShuffle: (String) -> Unit,
    onSmartTrackAction: (String, String, CanonicalCollectionIntent, Boolean) -> Unit,
    onCanonicalNormalTrackAction: (String, String, CanonicalCollectionIntent, Boolean) -> Unit,
    contentPadding: PaddingValues,
    query: String = "",
    searching: Boolean = false,
    onSearchingChange: (Boolean) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onResetFilters: () -> Unit = {},
    sort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
    filter: PlaylistLibraryTypeFilter = PlaylistLibraryTypeFilter.ALL,
    selectedCard: LibraryPlaylistCard? = null,
    onSelectedCardChange: (LibraryPlaylistCard?) -> Unit = {},
    pinnedPlaylistRequest: String? = null,
    onPinnedPlaylistRequestConsumed: () -> Unit = {},
    listState: LazyListState? = null,
    detailListState: LazyListState? = null,
    detailSearching: Boolean = false,
    onDetailSearchingChange: (Boolean) -> Unit = {},
    detailQuery: String = "",
    onDetailQueryChange: (String) -> Unit = {},
    onDetailRowsReady: (List<V2DisplayRow>?) -> Unit = {},
    detailSort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
    onPlaylistCountChange: (Int) -> Unit = {},
    currentCanonicalRecordingId: String? = null,
    isPlaying: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val playlistPinRepo = remember(context) { LibraryPlaylistPinRepository.get(context) }
    val pinnedPlaylists by playlistPinRepo.pins.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var optionsFor by remember { mutableStateOf<LibraryPlaylistCard?>(null) }
    var historicalEditor by remember { mutableStateOf<LibraryPlaylistCard.Normal?>(null) }
    var historicalEditTitle by remember { mutableStateOf("") }
    var historicalEditError by remember { mutableStateOf(false) }
    var historicalEditSaving by remember { mutableStateOf(false) }

    var deleteFailure by remember { mutableStateOf(false) }
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
    LaunchedEffect(cards.size, smartLoaded, normalLoaded) {
        if (smartLoaded && normalLoaded) onPlaylistCountChange(cards.size)
    }
    val openedCard = selectedCard?.let { latestLibraryPlaylistCard(it, cards) }
    LaunchedEffect(pinnedPlaylistRequest, cards, smartLoaded, normalLoaded) {
        val requested = pinnedPlaylistRequest ?: return@LaunchedEffect
        if (smartLoaded && normalLoaded) {
            val resolved = cards.firstOrNull { it.id == requested }
            if (resolved != null) {
                onSelectedCardChange(resolved)
            }
            onPinnedPlaylistRequestConsumed()
        }
    }
    val menuCard = optionsFor?.let { latestLibraryPlaylistCard(it, cards) }
    // Close options when a playlist disappears from the underlying Room store.
    LaunchedEffect(optionsFor?.id, cards) {
        if (optionsFor != null && smartLoaded && normalLoaded && menuCard == null) {
            optionsFor = null
        }
    }
    LaunchedEffect(selectedCard?.id, smartLoaded, normalLoaded, cards) {
        if (selectedCard != null && smartLoaded && normalLoaded && openedCard == null) {
            // Saved definition was deleted while this detail was open.
            onSelectedCardChange(null)
        }
    }
    LaunchedEffect(selectedCard?.id) {
        if (selectedCard != null) detailListState?.scrollToItem(0)
    }
    val visible = visibleLibraryPlaylistCards(cards, query, filter, sort)
    val playlistScroll = listState ?: rememberLazyListState()

    // The host owns status-bar and floating-player insets. Applying a second
    // statusBarsPadding() + 64dp here created an artificial gap and a second
    // page chrome inside Library. Use the same measured padding as LibraryScreen.
    if (selectedCard == null) LazyColumn(
        state = playlistScroll,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Page title belongs to the native floating top bar, not a second
        // scrollable header that may vanish or restore at a stale offset.
        // Apple Music keeps library-category search discoverable even when
        // the search keyboard is closed. Do not autofocus on normal entry.
        item(key = "playlist-search") {
            PlaylistLibrarySearchField(
                query = query,
                onQueryChange = onQueryChange,
                autoFocus = searching,
                onClose = {
                    onQueryChange("")
                    onSearchingChange(false)
                },
            )
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
                onSelectedCardChange(card)
            }
            UnifiedPlaylistCompactRow(
                card = card,
                onClick = openCard,
                onMore = { optionsFor = card },
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
    }
    menuCard?.let { selected ->
        val historicalMenuSongs = (selected as? LibraryPlaylistCard.Normal)
            ?.shelfItem?.browseId?.let(LocalPlaylistStore::getPlaylist)
            ?.songs?.let { songs ->
                eligibleHistoricalPlaylistSongs(songs) {
                    Downloads.verifiedSavedUri(it.videoId)
                }
            }.orEmpty()
        ModalBottomSheet(
            onDismissRequest = { optionsFor = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            BrowseActionsSheet(
                target = BrowseTarget(
                    browseId = null,
                    title = selected.title,
                    subtitle = when (selected) {
                        is LibraryPlaylistCard.Smart -> "Playlist inteligente"
                        else -> "Playlist normal"
                    },
                    thumbnailUrl = (selected as? LibraryPlaylistCard.Normal)?.shelfItem?.thumbnailUrl,
                    type = BrowseType.PLAYLIST,
                ),
                isPinned = pinnedPlaylists.any { it.id == selected.id },
                onTogglePin = {
                    playlistPinRepo.toggle(LibraryPlaylistPin(selected.id, selected.title))
                    optionsFor = null
                },
                onPlayNext = if (canQueueLibraryPlaylist(selected) || historicalMenuSongs.isNotEmpty()) ({
                    when (selected) {
                        is LibraryPlaylistCard.Smart ->
                            onSmartAction(selected.definition.id, CanonicalCollectionIntent.ADD_NEXT)
                        is LibraryPlaylistCard.CanonicalNormal ->
                            onCanonicalNormalAction(selected.definition.id, CanonicalCollectionIntent.ADD_NEXT)
                        is LibraryPlaylistCard.Normal -> onLegacyNext(historicalMenuSongs)
                    }
                    optionsFor = null
                }) else null,
                onAddToQueue = if (canQueueLibraryPlaylist(selected) || historicalMenuSongs.isNotEmpty()) ({
                    when (selected) {
                        is LibraryPlaylistCard.Smart ->
                            onSmartAction(selected.definition.id, CanonicalCollectionIntent.ADD_TO_QUEUE)
                        is LibraryPlaylistCard.CanonicalNormal ->
                            onCanonicalNormalAction(selected.definition.id, CanonicalCollectionIntent.ADD_TO_QUEUE)
                        is LibraryPlaylistCard.Normal -> onLegacyQueue(historicalMenuSongs)
                    }
                    optionsFor = null
                }) else null,
                onEdit = when (selected) {
                    is LibraryPlaylistCard.Smart -> ({
                        optionsFor = null
                        onOpenSmartPlaylist(selected.definition.id)
                    })
                    is LibraryPlaylistCard.CanonicalNormal -> ({
                        optionsFor = null
                        onOpenCanonicalNormalPlaylist(selected.definition.id)
                    })
                    is LibraryPlaylistCard.Normal -> ({
                        historicalEditTitle = selected.title
                        historicalEditError = false
                        historicalEditor = selected
                        optionsFor = null
                    })
                },
                onDelete = if (canDeleteLibraryPlaylist(selected)) ({
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                when (selected) {
                                    is LibraryPlaylistCard.Smart -> repo.remove(selected.definition.id)
                                    is LibraryPlaylistCard.CanonicalNormal ->
                                        normalRepo.remove(selected.definition.id)
                                    is LibraryPlaylistCard.Normal ->
                                        selected.shelfItem.browseId?.let(LocalPlaylistStore::deletePlaylist)
                                }
                            }
                            playlistPinRepo.remove(selected.id)
                            if (selectedCard?.id == selected.id) onSelectedCardChange(null)
                            optionsFor = null
                            deleteFailure = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            deleteFailure = true
                            Toast.makeText(context, "Não foi possível excluir a playlist", Toast.LENGTH_LONG).show()
                        }
                    }
                }) else null,
                onOpen = {
                    onSelectedCardChange(selected)
                    optionsFor = null
                },
            )
        }
    }
    historicalEditor?.let { editing ->
        ModalBottomSheet(
            onDismissRequest = {
                if (!historicalEditSaving) historicalEditor = null
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Editar playlist",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                PillTextField(
                    value = historicalEditTitle,
                    onValueChange = {
                        historicalEditTitle = it
                        historicalEditError = false
                    },
                    placeholder = "Nome da playlist",
                    enabled = !historicalEditSaving,
                )
                if (historicalEditError) {
                    Text(
                        "Informe um nome válido de até 240 caracteres.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { historicalEditor = null },
                        enabled = !historicalEditSaving,
                    ) { Text("Cancelar") }
                    TextButton(
                        onClick = {
                            val title = historicalEditTitle.trim()
                            if (title.isEmpty() || title.length > 240) {
                                historicalEditError = true
                            } else {
                                historicalEditSaving = true
                                scope.launch {
                                    try {
                                        val browseId = editing.shelfItem.browseId
                                            ?: error("Playlist sem identidade")
                                        withContext(Dispatchers.IO) {
                                            LocalPlaylistStore.renamePlaylist(browseId, title)
                                        }
                                        playlistPinRepo.rename(editing.id, title)
                                        historicalEditor = null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        historicalEditError = true
                                    } finally {
                                        historicalEditSaving = false
                                    }
                                }
                            }
                        },
                        enabled = !historicalEditSaving,
                    ) { Text("Salvar") }
                }
            }
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
            onShuffle = {
                when (selected) {
                    is LibraryPlaylistCard.Smart -> onSmartShuffle(selected.definition.id)
                    is LibraryPlaylistCard.CanonicalNormal ->
                        onCanonicalNormalShuffle(selected.definition.id)
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
            onLegacyPlay = onLegacyPlay,
            onLegacyShuffle = onLegacyShuffle,
            onLegacyQueue = onLegacyQueue,
            onLegacySongMenu = onLegacySongMenu,
            onLegacySongSwipe = onLegacySongSwipe,
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
            onAddSongs = {
                if (selected is LibraryPlaylistCard.CanonicalNormal) {
                    onAddSongsToCanonicalNormalPlaylist(selected.definition.id)
                }
            },
            onEditRules = {
                if (selected is LibraryPlaylistCard.Smart) {
                    onOpenSmartPlaylist(selected.definition.id)
                }
            },
            contentPadding = contentPadding,
            searching = detailSearching,
            onSearchingChange = onDetailSearchingChange,
            query = detailQuery,
            onQueryChange = onDetailQueryChange,
            onRowsReady = onDetailRowsReady,
            sort = detailSort,
            listState = detailListState,
            currentCanonicalRecordingId = currentCanonicalRecordingId,
            isPlaying = isPlaying,
        )
    }
}

/**
 * Cover identity and artwork loader are shared by the normal playlist Library
 * row AND the add-to-playlist destination picker. Never reimplement a second
 * thumbnail, collage, artwork URL or provider-specific title lookup.
 */
internal fun canonicalStaticPlaylistCoverCandidateIds(ids: List<String>): List<String> =
    ids.filter(String::isNotBlank).distinct().take(4)

@Composable
internal fun CanonicalStaticPlaylistCoverArt(
    playlist: SavedCanonicalStaticPlaylist,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val rows by produceState<List<V2DisplayRow>>(
        initialValue = emptyList(), key1 = playlist,
    ) {
        val coverIds = canonicalStaticPlaylistCoverCandidateIds(playlist.recordingIds)
        value = if (coverIds.isEmpty()) emptyList() else try {
            canonicalNormalPlaylistSelectedRows(
                context, coverIds, playlist.sourceProviderKey,
            ).filter { it.isAvailablePlaylistMember() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    CanonicalPlaylistCoverArt(
        rows = rows,
        modifier = modifier,
        customCoverUri = playlist.coverUri,
    )
}

/** Compact vertical Library presentation; the same actions work in the grid. */
@Composable
private fun UnifiedPlaylistCompactRow(
    card: LibraryPlaylistCard,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val canonicalCover by produceState<List<V2DisplayRow>>(
        initialValue = emptyList(), key1 = card.id, key2 = card,
    ) {
        value = when (card) {
            // The canonical normal cover is rendered by
            // CanonicalStaticPlaylistCoverArt, shared with the destination picker.
            is LibraryPlaylistCard.CanonicalNormal -> emptyList()
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
        onLongPress = onMore,
        trailing = {
            RowMoreButton(onClick = onMore, contentDescription = "Opções de ${card.title}")
        },
        modifier = modifier,
        horizontalPadding = 4.dp,
        // Keep Library rows compact. The large hero belongs to the
        // create/edit screen and the dedicated playlist detail only.
        artworkSize = 64.dp,
        artwork = when {
            card is LibraryPlaylistCard.CanonicalNormal -> ({
                CanonicalStaticPlaylistCoverArt(
                    card.definition, modifier = Modifier.fillMaxSize(),
                )
            })
            card.explicitCoverUri() != null -> ({
                CanonicalPlaylistCoverArt(
                    canonicalCover, Modifier.fillMaxSize(),
                    customCoverUri = card.explicitCoverUri(),
                )
            })
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
