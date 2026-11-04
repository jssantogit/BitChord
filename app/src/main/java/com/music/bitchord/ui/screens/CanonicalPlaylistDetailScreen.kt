package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.components.NativeDetailTrackRowFrame
import com.music.bitchord.ui.components.NativeDetailTrackSwipeContainer
import com.music.bitchord.ui.components.NativeDetailTrackArtwork
import com.music.bitchord.ui.components.NativeDetailTrackRatingSlot
import com.music.bitchord.ui.components.NativeTrackRowText
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.theme.AccentRed
import com.music.bitchord.ui.theme.rememberArtworkPalette
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Only catalog-backed, currently available and unsaved RecordingIds can be suggested. */
internal fun suggestedCanonicalPlaylistTracks(
    available: List<V2DisplayRow>,
    selectedIds: Set<String>,
    page: Int,
    shuffleSeed: Long = 0L,
): List<V2DisplayRow> {
    val candidates = available.distinctBy { it.id }
        .filter { it.id !in selectedIds && it.isAvailablePlaylistMember() }
    if (candidates.isEmpty()) return emptyList()
    // A shuffled bag is exhausted before any song repeats. Adjacent cycles
    // must also avoid replaying the final (possibly partial) page immediately.
    val pageCount = (candidates.size + 4) / 5
    val cycle = Math.floorDiv(page.coerceAtLeast(0), pageCount)
    val finalPageSize = ((candidates.size - 1) % 5) + 1
    var previousTail = emptySet<String>()
    var ordered = candidates
    for (index in 0..cycle) {
        val roundSeed = shuffleSeed xor (index.toLong() * -7046029254386353131L)
        val randomized = candidates.shuffled(kotlin.random.Random(roundSeed))
        ordered = if (index == 0 || candidates.size <= 5) {
            randomized
        } else {
            // Enough other recordings always exist for five new suggestions
            // when there are at least six candidates.
            val eligible = randomized.filterNot { it.id in previousTail }
            val deferred = randomized.filter { it.id in previousTail }
            eligible.take(5) + (eligible.drop(5) + deferred).shuffled(
                kotlin.random.Random(roundSeed xor -4658895280553007687L),
            )
        }
        previousTail = ordered.takeLast(finalPageSize).mapTo(HashSet()) { it.id }
    }
    return ordered.drop(Math.floorMod(page, pageCount) * 5).take(5)
}

/** Preserve the playlist's original ID, source, description and user-picked cover. */
internal fun appendSuggestedRecording(
    playlist: SavedCanonicalStaticPlaylist,
    recordingId: String,
): SavedCanonicalStaticPlaylist =
    if (recordingId in playlist.recordingIds) playlist
    else playlist.copy(recordingIds = playlist.recordingIds + recordingId)

/** Explicit user cover always takes priority over generated recording collages. */
internal fun LibraryPlaylistCard.explicitCoverUri(): String? = when (this) {
    is LibraryPlaylistCard.CanonicalNormal -> definition.coverUri
    is LibraryPlaylistCard.Smart -> definition.coverUri
    is LibraryPlaylistCard.Normal -> null
}

/** Repeat each sleeve only once in a collage, as the native playlist page does. */
internal fun distinctCanonicalPlaylistCoverRows(rows: List<V2DisplayRow>): List<V2DisplayRow> =
    rows.distinctBy { row ->
        row.localArtworkUri?.let { "local:$it" }
            ?: row.remoteArtworkRevision?.takeIf { it.isNotBlank() }
            ?: row.remoteMediaAssetId?.let { "remote:$it" }
            ?: row.id
    }.take(4)

/**
 * Artwork always comes from recordings actually in the saved collection.
 * No generated cover URLs, no legacy video IDs and no title-based album lookup.
 */
@Composable
internal fun CanonicalPlaylistCoverArt(
    rows: List<V2DisplayRow>,
    modifier: Modifier = Modifier,
    customCoverUri: String? = null,
) {
    val covers = remember(rows) { distinctCanonicalPlaylistCoverRows(rows) }
    when {
        customCoverUri != null -> Box(modifier.clip(RoundedCornerShape(12.dp))) {
            // As with native track artwork, preserve the automatic sleeve
            // beneath Coil. A revoked/broken document must not leave a blank cover.
            CanonicalPlaylistCoverArt(rows = rows, modifier = Modifier.fillMaxSize())
            AsyncImage(
                model = customCoverUri,
                contentDescription = "Capa personalizada",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        covers.size >= 4 -> Column(modifier.clip(RoundedCornerShape(12.dp))) {
            for (offset in listOf(0, 2)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (index in offset until offset + 2) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            SmartPlaylistPreviewArtwork(
                                row = covers[index], modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        covers.isNotEmpty() -> SmartPlaylistPreviewArtwork(
            row = covers.first(), modifier = modifier,
        )
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            PlaylistWizardArtworkPlaceholder(180.dp)
        }
    }
}

/** Same music-page metadata format as the native playlist header. */
internal fun canonicalPlaylistDetailMeta(
    smart: Boolean, rows: List<V2DisplayRow>?,
): String {
    val type = if (smart) "Playlist inteligente" else "Playlist"
    if (rows == null) return type
    val count = "${rows.size} " + if (rows.size == 1) "faixa" else "faixas"
    val durations = rows.map { it.durationMs }
    val total = if (durations.isNotEmpty() && durations.all { it != null }) {
        durations.filterNotNull().sum()
    } else 0L
    val minutes = total / 60000L
    val time = when {
        total <= 0L -> ""
        minutes >= 60L -> " · ${minutes / 60L}h ${minutes % 60L}min"
        else -> " · ${minutes}min"
    }
    return "${type} · ${count}${time}"
}

/** A saved RecordingId without eligible MediaAsset remains visible but is not clickable. */
internal fun V2DisplayRow.isAvailablePlaylistMember(): Boolean =
    title != "Gravação indisponível" || artist != "Fonte não disponível"

/** The same native Library row used everywhere, wired to canonical playback. */
/** Native SongRow's proven dimensions and controls, but canonical RecordingIds. */
/** Match the chosen remote rendition first; only fall back to a proven
 * canonical recording rating. No title joins, no provider IO in list rows. */
internal fun V2DisplayRow.canonicalPlaylistRating(
    snapshot: CanonicalRatingDisplaySnapshot,
): RatingDisplay? = remoteMediaAssetId?.let(snapshot.byMediaAssetId::get)
    ?: snapshot.byRecordingId[id]

internal fun canonicalTrackDuration(durationMs: Long?): String? =
    durationMs?.takeIf { it > 0 }?.let { ms ->
        val seconds = ms / 1000L
        if (seconds >= 3600L) "%d:%02d:%02d".format(
            seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L,
        ) else "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

@Composable
internal fun CanonicalPlaylistTrackRow(
    track: V2DisplayRow,
    onPlay: (String) -> Unit,
    onQueue: ((String, CanonicalCollectionIntent) -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
    subtitleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    rowBackground: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
    activeTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    val available = track.isAvailablePlaylistMember()
    val swipeSetting = com.music.bitchord.ui.AppUi.hostOrNull?.swipeToPlayNext
    val playNext = swipeSetting?.collectAsStateWithLifecycle()?.value ?: false
    val onSwipeToQueue = if (available && onQueue != null) {
        {
            onQueue(
                track.id,
                canonicalPlaylistSwipeIntent(playNext),
            )
        }
    } else null
    NativeDetailTrackSwipeContainer(
        onSwipeToQueue = onSwipeToQueue,
        // The row must mask the queue reveal on an artwork-tinted page.
        rowBackground = rowBackground,
    ) { rowModifier ->
    NativeDetailTrackRowFrame(
        modifier = rowModifier.then(
            if (isCurrent) Modifier.testTag("canonical-playlist-current-track")
            else Modifier,
        ),
        background = if (isCurrent) activeTint.copy(alpha = 0.14f)
            else androidx.compose.ui.graphics.Color.Transparent,
        onClick = if (available) ({ onPlay(track.id) }) else null,
        onLongPress = if (available) ({ menuExpanded = true }) else null,
        onMore = if (available) ({ menuExpanded = true }) else null,
        opacity = if (available) 1f else 0.45f,
    ) {
        NativeDetailTrackArtwork(showPlayingBars = isCurrent && isPlaying) {
            SmartPlaylistPreviewArtwork(
                row = track, modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(14.dp))
        NativeTrackRowText(
            title = {
                Text(
                    track.title, style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) activeTint else titleColor, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    track.artist, style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        // Same rating strip as other native song lists. Keep duration in
        // metadata for sorting/planning, never as a trailing row label.
        NativeDetailTrackRatingSlot {
            CanonicalRatingStars(
                rating = track.canonicalPlaylistRating(LocalCanonicalRatingDisplay.current),
                color = titleColor,
            )
        }
        if (available) {
            Box {
                RowMoreButton(
                    onClick = { menuExpanded = true }, tint = subtitleColor,
                    contentDescription = "Ações da música",
                )
                if (menuExpanded) {
                    CanonicalTrackActionSheet(
                        onDismiss = { menuExpanded = false },
                        onPlay = { onPlay(track.id) },
                        onPlayNext = onQueue?.let { queue ->
                            { queue(track.id, CanonicalCollectionIntent.ADD_NEXT) }
                        },
                        onAddToQueue = onQueue?.let { queue ->
                            { queue(track.id, CanonicalCollectionIntent.ADD_TO_QUEUE) }
                        },
                    )
                }
            }
        }
    }
    }
}

/** The caller supplies only a verified canonical RecordingId, never a title or video ID. */
internal fun isVerifiedCanonicalPlaylistCurrentTrack(
    recordingId: String,
    currentCanonicalRecordingId: String?,
): Boolean = recordingId.isNotBlank() && currentCanonicalRecordingId != null &&
    recordingId == currentCanonicalRecordingId

/** The exact same queue intent selected by the shared SongRow swipe setting. */
internal fun canonicalPlaylistSwipeIntent(playNext: Boolean): CanonicalCollectionIntent =
    if (playNext) CanonicalCollectionIntent.ADD_NEXT
    else CanonicalCollectionIntent.ADD_TO_QUEUE

/**
 * Native action-row presentation reused from SongActionsSheet / BrowseActionsSheet.
 * The actions carry only the clicked canonical RecordingId, never a legacy Song.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CanonicalTrackActionSheet(
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            ActionRow(Icons.Rounded.PlayArrow, "Reproduzir daqui") {
                onPlay()
                onDismiss()
            }
            onPlayNext?.let { next ->
                ActionRow(Icons.Rounded.SkipNext, "Reproduzir a seguir") {
                    next()
                    onDismiss()
                }
            }
            onAddToQueue?.let { queue ->
                ActionRow(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "Adicionar à fila",
                    modifier = Modifier.testTag("canonical-track-queue-action"),
                ) {
                    queue()
                    onDismiss()
                }
            }
        }
    }
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
    onShuffle: () -> Unit,
    onTrackClick: (String) -> Unit,
    onTrackQueue: (String, CanonicalCollectionIntent) -> Unit,
    onAddSongs: () -> Unit = {},
    onEditRules: () -> Unit = {},
    contentPadding: PaddingValues,
    searching: Boolean = false,
    onSearchingChange: (Boolean) -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onRowsReady: (List<V2DisplayRow>?) -> Unit = {},
    sort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
    listState: LazyListState? = null,
    currentCanonicalRecordingId: String? = null,
    isPlaying: Boolean = false,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current.applicationContext
    val normalRepository = remember(context) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
    }
    val scope = rememberCoroutineScope()
    var suggestionPage by remember(card.id) { mutableStateOf(0) }
    val suggestionShuffleSeed = remember(card.id) { kotlin.random.Random.nextLong() }
    var pendingSuggestionId by remember(card.id) { mutableStateOf<String?>(null) }
    var suggestionError by remember(card.id) { mutableStateOf(false) }
    var trackLoadFailed by remember(card.id) { mutableStateOf(false) }
    val source = (card as? LibraryPlaylistCard.CanonicalNormal)?.definition?.sourceProviderKey
    val availableSuggestions by produceState(
        initialValue = emptyList<V2DisplayRow>(), key1 = card.id, key2 = source,
    ) {
        if (card is LibraryPlaylistCard.CanonicalNormal) {
            value = try {
                canonicalNormalPlaylistSearch(context, "", source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val suggestions = suggestedCanonicalPlaylistTracks(
        availableSuggestions,
        (card as? LibraryPlaylistCard.CanonicalNormal)
            ?.definition?.recordingIds.orEmpty().toSet(),
        suggestionPage,
        shuffleSeed = suggestionShuffleSeed,
    )
    fun addSuggested(id: String) {
        val playlist = card as? LibraryPlaylistCard.CanonicalNormal ?: return
        if (pendingSuggestionId != null || id !in suggestions.map { it.id }) return
        pendingSuggestionId = id
        suggestionError = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Reload at tap time: never overwrite a concurrent edit of
                    // the same canonical playlist with a stale detail snapshot.
                    val current = normalRepository.list().firstOrNull {
                        it.id == playlist.definition.id
                    } ?: error("Playlist indisponível")
                    normalRepository.save(appendSuggestedRecording(current, id))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                suggestionError = true
            } finally {
                pendingSuggestionId = null
            }
        }
    }
    val rows by produceState<List<V2DisplayRow>?>(initialValue = null, key1 = card.id, key2 = card) {
        value = try {
            trackLoadFailed = false
            when (card) {
                is LibraryPlaylistCard.CanonicalNormal ->
                    canonicalNormalPlaylistSelectedRows(
                        context, card.definition.recordingIds, card.definition.sourceProviderKey,
                    )
                is LibraryPlaylistCard.Smart -> {
                    val ids = withContext(Dispatchers.IO) {
                        // Building the rule evaluator can read Room configuration.
                        // Keep it away from the main thread, especially in a lazy shelf.
                        persistentSmartPlaylistPreview(context)
                            .preview(card.definition.query, card.definition.id)
                            .map { it.recordingId }
                    }
                    canonicalNormalPlaylistSelectedRows(context, ids)
                }
                is LibraryPlaylistCard.Normal -> emptyList()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            trackLoadFailed = true
            emptyList()
        }
    }
    LaunchedEffect(card.id, rows) {
        onRowsReady(rows)
    }
    val visibleRows = remember(rows, query, sort) {
        val filtered = rows.orEmpty().filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }
        when (sort) {
            PlaylistLibrarySort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            PlaylistLibrarySort.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            PlaylistLibrarySort.DEFAULT -> filtered
        }
    }
    val palette = rememberArtworkPalette(imageUrl = rows?.firstOrNull()?.localArtworkUri)
    val playlistDescription = when (card) {
        is LibraryPlaylistCard.CanonicalNormal -> card.definition.description
        is LibraryPlaylistCard.Smart -> card.definition.description
        is LibraryPlaylistCard.Normal -> ""
    }.takeIf { it.isNotBlank() }
    val canPlay = rows?.isNotEmpty() == true &&
        (rows?.size ?: 0) <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE &&
        rows.orEmpty().all { it.isAvailablePlaylistMember() }

    NativePlaylistDetailFrame(
        palette = palette,
        listState = listState ?: rememberLazyListState(),
        bottomPadding = contentPadding.calculateBottomPadding(),
    ) {
        item(key = "playlist-detail-cover") {
            // The same hero frame used by DetailScreen.ReleaseHeader; the
            // playlist still passes real RecordingIds to its playback handoff.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            NativeReleaseHeaderLayout(
                title = card.title,
                credit = "",
                meta = canonicalPlaylistDetailMeta(
                    smart = card is LibraryPlaylistCard.Smart, rows = rows,
                ),
                palette = palette,
                // Exactly the same 76% frame as DetailScreen's native release hero.
                sleeveSize = maxWidth * 0.76f,
                artwork = {
                    CanonicalPlaylistCoverArt(
                        rows = rows.orEmpty(), modifier = Modifier.fillMaxSize(),
                        customCoverUri = card.explicitCoverUri(),
                    )
                },
                actions = {
                    if (canPlay) {
                        Spacer(Modifier.height(18.dp))
                        NativeReleaseActionRow(
                            onPlay = { onAction(CanonicalCollectionIntent.PLAY_NOW) },
                            onShuffle = onShuffle,
                            isLocal = false,
                            onAddToQueue = {
                                onAction(CanonicalCollectionIntent.ADD_TO_QUEUE)
                            },
                        )
                    }
                }
            )
            }
        }
        if (playlistDescription != null) {
            item(key = "playlist-detail-description") {
                AboutSection(
                    title = "Sobre a playlist",
                    text = playlistDescription,
                    palette = palette,
                    horizontalPadding = PAGE_GUTTER,
                )
            }
        }
        if (searching || query.isNotBlank()) {
            item(key = "playlist-detail-search") {
                DetailSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClose = {
                        onQueryChange("")
                        onSearchingChange(false)
                    },
                    autoFocus = searching,
                    onFocused = {},
                    palette = palette,
                )
            }
        }
        if (rows == null) item(key = "playlist-detail-loading") {
            NativeLoadingSpinner()
        }
        if (rows?.isEmpty() == true) {
            if (trackLoadFailed) {
                item(key = "playlist-detail-load-error") {
                    Text(
                        "Não foi possível consultar as músicas desta playlist.",
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (card is LibraryPlaylistCard.CanonicalNormal) {
                item(key = "playlist-detail-empty-add-and-suggestions") {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PlaylistWizardSecondaryAction(
                            label = "Adicionar músicas",
                            onClick = onAddSongs,
                        )
                        if (availableSuggestions.isNotEmpty()) {
                            SettingsGroupSurface {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(
                                        start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Músicas sugeridas",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            "Adicione faixas da biblioteca à playlist",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = { suggestionPage++ },
                                        enabled = availableSuggestions.size > 5,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Refresh,
                                            contentDescription = "Atualizar sugestões",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                suggestions.forEach { suggested ->
                                    LibraryRow(
                                        title = suggested.title,
                                        subtitle = suggested.artist,
                                        imageUrl = null,
                                        titleColor = MaterialTheme.colorScheme.onSurface,
                                        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        artwork = {
                                            SmartPlaylistPreviewArtwork(
                                                suggested, Modifier.fillMaxSize(),
                                            )
                                        },
                                        horizontalPadding = 16.dp,
                                        trailing = {
                                            IconButton(
                                                onClick = { addSuggested(suggested.id) },
                                                enabled = pendingSuggestionId == null,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.AddCircleOutline,
                                                    contentDescription = "Adicionar ${suggested.title}",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        },
                                    )
                                }
                                if (suggestionError) {
                                    Text(
                                        "Não foi possível adicionar a música.",
                                        modifier = Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (card is LibraryPlaylistCard.Smart) {
                // Smart membership is rule-driven, never mutate an invented
                // manual track list. The native rule editor is the only action.
                item(key = "playlist-detail-empty-smart-edit") {
                    Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 16.dp)) {
                        PlaylistWizardSecondaryAction(
                            label = "Editar regras",
                            onClick = onEditRules,
                        )
                    }
                }
            }
        }
        items(visibleRows, key = { it.id }) { track ->
            CanonicalPlaylistTrackRow(
                track = track,
                onPlay = onTrackClick,
                onQueue = onTrackQueue,
                titleColor = palette.onBackground,
                subtitleColor = palette.onBackgroundVariant,
                rowBackground = palette.background,
                activeTint = palette.accent,
                isCurrent = isVerifiedCanonicalPlaylistCurrentTrack(
                    track.id, currentCanonicalRecordingId,
                ),
                isPlaying = isPlaying,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                thickness = 0.5.dp, color = palette.divider,
            )
        }
    }
}
