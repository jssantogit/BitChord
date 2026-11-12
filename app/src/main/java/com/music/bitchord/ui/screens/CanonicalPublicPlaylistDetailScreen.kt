package com.music.bitchord.ui.screens

import com.music.bitchord.data.canvas.VisualArtworkCandidate
import com.music.bitchord.data.canvas.VisualArtworkPriority
import com.music.bitchord.data.canvas.VisualArtworkPrefetch
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTrack

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistDetail
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.NativeDetailTrackArtwork
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.NativeInlineTextAction
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Warm only the first visible public sleeves and the exact hero.
 * The shared Coil planner enforces the same trusted-URL policy everywhere. */
internal fun publicPlaylistWarmupCandidates(
    cover: String?,
    tracks: List<ListenBrainzPublicPlaylistTrack>,
): List<VisualArtworkCandidate> = buildList {
    add(VisualArtworkCandidate(cover, VisualArtworkPriority.HERO, 900))
    tracks.take(8).forEach { track ->
        add(VisualArtworkCandidate(track.artworkUrl,
            VisualArtworkPriority.VISIBLE, ROW_ART_PX))
    }
}

/**
 * The SAME native playlist detail frame and header used by Library playlists.
 * This public read-only adapter never saves a remote list as a user's playlist.
 * Play is offered only when the exact RecordingId resolves to an available
 * canonical MediaAsset in the unified Library.
 */
@Composable
internal fun CanonicalPublicPlaylistDetailScreen(
    playlist: ListenBrainzPublicPlaylist,
    state: UiState<ListenBrainzPublicPlaylistDetail>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onVerifiedRecordingPlay: (String) -> Unit,
    searching: Boolean = false,
    onSearchingChange: (Boolean) -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    sort: PlaylistLibrarySort = PlaylistLibrarySort.DEFAULT,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val pinRepo = remember(context) { LibraryPlaylistPinRepository.get(context) }
    val playlistPins by pinRepo.pins.collectAsStateWithLifecycle()
    val pinnedId = "public:" + playlist.mbid
    val pinned = playlistPins.any { it.id == pinnedId }
    val ratingTarget = remember(playlist.mbid) { canonicalPublicPlaylistRatingTarget(playlist.mbid) }
    val rating by rememberCanonicalCatalogEntityRating(ratingTarget)
    var editingRating by remember(playlist.mbid) { mutableStateOf(false) }
    if (editingRating && ratingTarget != null) {
        CanonicalCatalogEntityRatingDialog(
            target = ratingTarget, title = "Avaliar playlist",
            onDismiss = { editingRating = false },
        )
    }
    var editingRecording by remember(playlist.mbid) { mutableStateOf<String?>(null) }
    var addingRecording by remember(playlist.mbid) {
        mutableStateOf<Triple<String, String, String>?>(null)
    }
    addingRecording?.let { (recording, title, artist) ->
        CanonicalMetadataRecordingPlaylistPicker(
            recordingId = recording, title = title, artist = artist,
            onDismiss = { addingRecording = null },
        )
    }
    editingRecording?.let { recordingId ->
        CanonicalMetadataRecordingRatingDialog(
            recordingId = recordingId, onDismiss = { editingRecording = null },
        )
    }
    val entries = (state as? UiState.Success)?.data?.tracks.orEmpty()
    val artworkWarmup = remember(playlist.mbid, playlist.artworkUrl, entries) {
        publicPlaylistWarmupCandidates(playlist.artworkUrl, entries)
    }
    LaunchedEffect(artworkWarmup) {
        VisualArtworkPrefetch.warm(context, artworkWarmup)
    }
    // Keep original public order and unique row identity even after sorting.
    val visibleEntries = remember(entries, query, sort) {
        val filtered = entries.withIndex().filter { (_, track) ->
            track.title.contains(query, ignoreCase = true) ||
                track.artist.contains(query, ignoreCase = true)
        }
        when (sort) {
            PlaylistLibrarySort.TITLE_ASC -> filtered.sortedWith(
                compareBy<IndexedValue<com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTrack>> {
                    it.value.title.lowercase()
                }.thenBy { it.index },
            )
            PlaylistLibrarySort.TITLE_DESC -> filtered.sortedWith(
                compareByDescending<IndexedValue<com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistTrack>> {
                    it.value.title.lowercase()
                }.thenBy { it.index },
            )
            PlaylistLibrarySort.DEFAULT -> filtered
        }
    }
    val ids = entries.mapNotNull { it.recordingMbid }.distinct()
    val catalogRows by produceState<List<V2DisplayRow>>(
        initialValue = emptyList(), key1 = playlist.mbid, key2 = ids,
    ) {
        value = if (ids.isEmpty()) emptyList() else try {
            withContext(Dispatchers.IO) {
                canonicalNormalPlaylistSelectedRows(context, ids)
                    .filter { it.isAvailablePlaylistMember() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
    }
    val availableIds = catalogRows.mapTo(HashSet()) { it.id }
    val palette = rememberArtworkPalette(imageUrl = playlist.artworkUrl)
    NativePlaylistDetailFrame(
        palette = palette,
        listState = listState,
        bottomPadding = contentPadding.calculateBottomPadding(),
    ) {
        item(key = "public-playlist-cover") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                NativePlaylistDetailHeader(
                    title = playlist.title,
                    credit = playlist.creator,
                    meta = "Playlist · ${entries.size} " +
                        if (entries.size == 1) "faixa" else "faixas",
                    palette = palette,
                    rating = rating,
                    onRate = if (ratingTarget != null) ({ editingRating = true }) else null,
                    sleeveSize = maxWidth * .76f,
                    artwork = {
                        Box(Modifier.fillMaxSize()) {
                            CanonicalPlaylistCoverArt(
                                rows = emptyList(), modifier = Modifier.fillMaxSize(),
                            )
                            AsyncImage(
                                model = playlist.artworkUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                    // The same release/playlist action row as Library. Public
                    // membership has no queue asset as a collection; keep
                    // playback controls visible but disabled. Pin is local.
                    actions = {
                        Spacer(Modifier.height(18.dp))
                        NativeReleaseActionRow(
                            onPlay = {},
                            onShuffle = {},
                            isLocal = false,
                            canPlay = false,
                            canShuffle = false,
                            reservePinAction = true,
                            pinTargetLabel = "playlist",
                            isPinnedInLibrary = pinned,
                            onTogglePin = {
                                pinRepo.toggle(LibraryPlaylistPin(
                                    id = pinnedId, title = playlist.title,
                                    creator = playlist.creator,
                                ))
                            },
                        )
                    },
                )
            }
        }
        if (searching || query.isNotBlank()) {
            item(key = "public-playlist-search") {
                NativeDetailSearchField(
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
        playlist.description?.takeIf { it.isNotBlank() }?.let { description ->
            item(key = "public-playlist-description") {
                AboutSection(
                    title = "Sobre a playlist",
                    text = description,
                    palette = palette,
                    horizontalPadding = PAGE_GUTTER,
                )
            }
        }
        when (state) {
            UiState.Loading -> item(key = "public-playlist-loading") {
                NativeLoadingSpinner()
            }
            is UiState.Error -> item(key = "public-playlist-error") {
                Text(state.message, color = palette.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER))
                NativeInlineTextAction(
                    label = "Tentar novamente",
                    onClick = onRetry,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                )
            }
            is UiState.Success -> {
                if (entries.isEmpty()) item(key = "public-playlist-empty") {
                    Text("Esta playlist pública não contém faixas disponíveis.",
                        modifier = Modifier.padding(PAGE_GUTTER),
                        color = palette.onBackgroundVariant)
                }
                items(visibleEntries.size, key = { "public-playlist-track:" + visibleEntries[it].index }) { index ->
                    val indexed = visibleEntries[index]
                    val originalIndex = indexed.index
                    val entry = indexed.value
                    val recordingId = entry.recordingMbid
                    val playable = recordingId != null && recordingId in availableIds
                    val coverUri = entry.artworkUrl
                        ?: catalogRows.firstOrNull { it.id == recordingId }?.localArtworkUri
                    var menuExpanded by remember(playlist.mbid, originalIndex) {
                        mutableStateOf(false)
                    }
                    NativeDetailTrackRowLayout(
                        onClick = if (playable && recordingId != null)
                            ({ onVerifiedRecordingPlay(recordingId) }) else null,
                        onLongPress = if (recordingId?.let(::isVerifiedCatalogRecordingId) == true)
                            ({ menuExpanded = true }) else null,
                        onMore = if (recordingId?.let(::isVerifiedCatalogRecordingId) == true)
                            ({ menuExpanded = true }) else null,
                        opacity = 1f,
                        leading = {
                            NativeDetailTrackArtwork(showPlayingBars = false) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.MusicNote, contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!coverUri.isNullOrBlank()) {
                                        AsyncImage(
                                            model = coverUri.artworkAt(ROW_ART_PX),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        },
                        title = {
                            Text(entry.title, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.onBackground)
                        },
                        subtitle = {
                            Text(
                                entry.artist,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = palette.onBackgroundVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        rating = {},
                        showRatingSlot = false,
                        actions = {
                            CanonicalMetadataTrackMore(
                                recordingId = recordingId,
                                title = entry.title,
                                artist = entry.artist,
                                palette = palette,
                                expanded = menuExpanded,
                                onOpen = { menuExpanded = true },
                                onDismiss = { menuExpanded = false },
                                onRate = { editingRecording = it },
                                onAddToPlaylist = { id, title, artist ->
                                    addingRecording = Triple(id, title, artist)
                                },
                                tag = "public-playlist-track-more-$originalIndex",
                            )
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        thickness = .5.dp,
                        color = palette.divider,
                    )
                }
            }
        }
    }
}
