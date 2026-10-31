package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.components.NativeDetailTrackRowFrame
import com.music.bitchord.ui.theme.AccentRed
import com.music.bitchord.ui.theme.rememberArtworkPalette
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) {
    val covers = remember(rows) { distinctCanonicalPlaylistCoverRows(rows) }
    when {
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
    if (rows == null) return "${type} · Carregando músicas…"
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
) {
    var menuExpanded by remember(track.id) { mutableStateOf(false) }
    val available = track.isAvailablePlaylistMember()
    NativeDetailTrackRowFrame(
        onClick = if (available) ({ onPlay(track.id) }) else null,
        onMore = if (available) ({ menuExpanded = true }) else null,
        opacity = if (available) 1f else 0.45f,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        ) {
            SmartPlaylistPreviewArtwork(
                row = track, modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, style = MaterialTheme.typography.titleMedium,
                color = titleColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                track.artist, style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        canonicalTrackDuration(track.durationMs)?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = subtitleColor)
        }
        if (available) {
            Box {
                RowMoreButton(
                    onClick = { menuExpanded = true }, tint = subtitleColor,
                    contentDescription = "Ações da música",
                )
                DropdownMenu(
                    expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproduzir daqui") },
                        onClick = { menuExpanded = false; onPlay(track.id) },
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
    contentPadding: PaddingValues,
    searching: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    sort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
    listState: LazyListState? = null,
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
            emptyList()
        }
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
    val canPlay = rows?.isNotEmpty() == true &&
        (rows?.size ?: 0) <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE &&
        rows.orEmpty().all { it.isAvailablePlaylistMember() }

    LazyColumn(
        state = listState ?: rememberLazyListState(),
        modifier = Modifier.fillMaxSize().background(palette.background),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
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
                    )
                },
                actions = {
                    if (canPlay) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = PAGE_GUTTER, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircleIconButton(
                                icon = BitChordIcons.Shuffle,
                                contentDescription = "Aleatório",
                                onClick = onShuffle,
                                tint = palette.onBackground,
                            )
                            ActionPill(
                                icon = BitChordIcons.Play, label = "Tocar",
                                onClick = { onAction(CanonicalCollectionIntent.PLAY_NOW) },
                                filled = true, modifier = Modifier.weight(1f),
                            )
                            CircleIconButton(
                                icon = BitChordIcons.Plus,
                                contentDescription = "Adicionar à fila",
                                onClick = { onAction(CanonicalCollectionIntent.ADD_TO_QUEUE) },
                                tint = palette.onBackground,
                            )
                        }
                    }
                }
            )
            }
        }
        if (searching || query.isNotBlank()) {
            item(key = "playlist-detail-search") {
                PillTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "Buscar nesta playlist",
                    accessibilityLabel = "Buscar nesta playlist",
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER)
                        .height(52.dp),
                )
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
        items(visibleRows, key = { it.id }) { track ->
            CanonicalPlaylistTrackRow(
                track = track,
                onPlay = onTrackClick,
                onQueue = onTrackQueue,
                titleColor = palette.onBackground,
                subtitleColor = palette.onBackgroundVariant,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                thickness = 0.5.dp, color = palette.divider,
            )
        }
    }
}
