package com.music.bitchord.ui.screens

import android.graphics.BitmapFactory
import coil3.compose.AsyncImage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.CanonicalLibraryBrowseRepository
import com.music.bitchord.data.catalog.CanonicalMultiSourceBrowseRepository
import com.music.bitchord.data.catalog.RoomCanonicalMultiSourceBrowseStore
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.RoomCanonicalLibraryBrowseStore
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.CanonicalContextualFilterLibrary
import com.music.bitchord.data.listening.CanonicalSmartFilterContext
import com.music.bitchord.data.listening.filterCanonicalBrowseAlbums
import com.music.bitchord.data.listening.filterCanonicalBrowseArtists
import com.music.bitchord.data.listening.isSupportedForCanonicalAlbumBrowse
import com.music.bitchord.data.listening.isSupportedForCanonicalArtistBrowse
import com.music.bitchord.data.listening.SmartPlaylistV2FilterRepository
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Filter
import com.music.bitchord.data.listening.filterCanonicalBrowseSongs
import com.music.bitchord.data.listening.isSupportedForCanonicalSongBrowse
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.referencedFields
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoadResult
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.data.catalog.provider.opensubsonic.androidOpenSubsonicArtworkLoader
import com.music.bitchord.playback.CanonicalRemotePlaybackMetadata
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.playCanonicalRemote
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.canonical_rating_song_options
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import org.jetbrains.compose.resources.stringResource as sharedStringResource
import com.music.bitchord.ui.player.awaitRecoverableCanonicalArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private sealed interface CanonicalSongFilterState {
    data object Loading : CanonicalSongFilterState
    data object Failed : CanonicalSongFilterState
    data class Ready(val tracks: List<CanonicalBrowseTrack>) : CanonicalSongFilterState
}

private sealed interface CanonicalEntityBrowseState {
    data object Loading : CanonicalEntityBrowseState
    data object Failed : CanonicalEntityBrowseState
    data class Albums(val releases: List<ReleaseEntity>) : CanonicalEntityBrowseState
    data class Artists(val artists: List<CanonicalSelectedEntity>) : CanonicalEntityBrowseState
}

private sealed interface CanonicalDetailBrowseState {
    data object Loading : CanonicalDetailBrowseState
    data object Failed : CanonicalDetailBrowseState
    data class Ready(val recordingIds: Set<String>) : CanonicalDetailBrowseState
}

private data class CanonicalSelectedEntity(val id: String, val name: String)

/**
 * Offline-first browse for a provider's persisted canonical availability.
 *
 * Metadata remains Room-only. Artwork is the one bounded exception: rows hand
 * only their canonical mediaAssetId to a provider-owned loader which may serve
 * a credential-free disk cache or authenticate a cover-art request at runtime.
 * Authenticated URLs and provider cover IDs never enter Compose state.
 */
@Composable
internal fun CanonicalRemoteLibraryScreen(
    providerKey: String,
    title: String,
    onClose: () -> Unit,
    unified: Boolean = false,
    filterActionToken: Int = 0,
    searchVisible: Boolean = false,
    detailBackToken: Int = 0,
    onDetailChanged: (Boolean) -> Unit = {},
    initialContext: CanonicalSmartFilterContext = CanonicalSmartFilterContext.SONGS,
) {
    val context = LocalContext.current
    val controller = rememberMediaController()
    val catalogDb = remember(context.applicationContext) {
        CanonicalCatalogDatabase.get(context.applicationContext)
    }
    val catalogDao = remember(catalogDb) { catalogDb.catalogDao() }
    val contextualReader = remember(catalogDao) { CanonicalContextualFilterLibrary(catalogDao) }
    val repository = remember(context.applicationContext) {
        CanonicalLibraryBrowseRepository(
            RoomCanonicalLibraryBrowseStore(
                catalogDao,
            ),
        )
    }
    val artworkLoader = remember(context.applicationContext) {
        androidOpenSubsonicArtworkLoader(context.applicationContext)
    }
    val sourceAccounts = remember(context.applicationContext) {
        SharedPrefsOpenSubsonicAccountPersistence(context.applicationContext)
    }
    val availableKeys by produceState(
        initialValue = listOf("local-mediastore"), key1 = sourceAccounts,
    ) {
        if (unified) {
            sourceAccounts.observe().collectLatest {
                value = withContext(Dispatchers.IO) {
                    canonicalAvailablePlaylistSourceKeys(context.applicationContext)
                }
            }
        }
    }
    var selectedSource by remember(unified) { mutableStateOf<String?>(null) }
    var libraryQuery by remember(unified) { mutableStateOf("") }
    LaunchedEffect(searchVisible) { if (!searchVisible) libraryQuery = "" }
    var selectedStars by remember(unified) { mutableStateOf<Int?>(null) }
    val sources = if (unified) availableKeys else listOf(providerKey)
    val multiRepository = remember(catalogDao) {
        CanonicalMultiSourceBrowseRepository(RoomCanonicalMultiSourceBrowseStore(catalogDao))
    }
    val libraryFlow = remember(repository, multiRepository, sources, providerKey, unified) {
        if (unified) multiRepository.observe(sources.toSet())
        else repository.observe(providerKey)
    }
    val library by libraryFlow.collectAsStateWithLifecycle(initialValue = CanonicalBrowseLibrary.EMPTY)
    val ratingsSnapshot = LocalCanonicalRatingDisplay.current
    val eligibleTracks = remember(library.tracks, selectedSource, selectedStars, ratingsSnapshot, unified) {
        if (unified) filterCanonicalLibraryTracks(
            library.tracks, selectedSource, selectedStars, ratingsSnapshot,
        ) else library.tracks
    }
    val executeLibraryTrack = rememberSmartCollectionBatchAction(controller)
    // Preserve native RecordingId-scoped rating edits from the main branch.
    var editingRecordingId by remember { mutableStateOf<String?>(null) }
    val filterRepo = remember(context.applicationContext) {
        SmartPlaylistV2FilterRepository(
            SmartPlaylistDefinitionDatabase.get(context.applicationContext).v2FilterDao(),
        )
    }
    var filtersUnavailable by remember(providerKey) { mutableStateOf(false) }
    val filters by produceState<List<SavedSmartPlaylistV2Filter>>(
        initialValue = emptyList(), key1 = filterRepo, key2 = providerKey,
    ) {
        try {
            filterRepo.observe().collect { value = it; filtersUnavailable = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            filtersUnavailable = true
        }
    }
    var selectedContext by remember(providerKey, initialContext) {
        mutableStateOf(initialContext)
    }
    var selectedEntity by remember(providerKey, selectedContext) {
        mutableStateOf<CanonicalSelectedEntity?>(null)
    }
    LaunchedEffect(detailBackToken) {
        if (detailBackToken > 0) selectedEntity = null
    }
    LaunchedEffect(selectedEntity != null) {
        onDetailChanged(selectedEntity != null)
    }
    DisposableEffect(Unit) {
        onDispose { onDetailChanged(false) }
    }
    var selectedFilterId by remember(providerKey, selectedContext) {
        mutableStateOf<String?>(null)
    }
    val compatible = remember(filters, selectedContext, providerKey) {
        filters.filter { filter ->
            !unified && when (selectedContext) {
                CanonicalSmartFilterContext.SONGS ->
                    filter.isSupportedForCanonicalSongBrowse(providerKey)
                CanonicalSmartFilterContext.ALBUMS ->
                    filter.isSupportedForCanonicalAlbumBrowse()
                CanonicalSmartFilterContext.ARTISTS ->
                    filter.isSupportedForCanonicalArtistBrowse()
            }
        }
    }
    val selected = filters.firstOrNull { it.id == selectedFilterId }
    // Genre/mood metadata may change while Recording display rows stay equal.
    // Listen to the tags table directly; all reads still use provider-scoped SQL.
    val tagEvidenceRevision by produceState(initialValue = 0L, key1 = catalogDb) {
        catalogDb.invalidationTracker.createFlow("catalog_media_asset_tags").collect {
            value += 1L
        }
    }
    val filteredState by produceState<CanonicalSongFilterState>(
        initialValue = CanonicalSongFilterState.Loading,
        key1 = library.tracks, key2 = selectedFilterId,
        key3 = (Triple(selectedContext, selected, filtersUnavailable) to tagEvidenceRevision)
            to (Triple(selectedSource, selectedStars, ratingsSnapshot) to libraryQuery),
    ) {
        value = try {
            when {
                selectedContext != CanonicalSmartFilterContext.SONGS ->
                    CanonicalSongFilterState.Ready(emptyList())
                filtersUnavailable && selectedFilterId != null -> CanonicalSongFilterState.Failed
                selectedFilterId == null -> CanonicalSongFilterState.Ready(
                    if (unified && libraryQuery.isNotBlank()) eligibleTracks.filter {
                        it.title.contains(libraryQuery, ignoreCase = true) ||
                            it.artistDisplay.contains(libraryQuery, ignoreCase = true)
                    } else eligibleTracks,
                )
                selected == null -> CanonicalSongFilterState.Failed
                else -> {
                    val tagRows = if (selected.rules.referencedFields().any {
                        it == SmartPlaylistRuleField.GENRE || it == SmartPlaylistRuleField.MOOD ||
                            it == SmartPlaylistRuleField.BPM ||
                            it == SmartPlaylistRuleField.STYLE ||
                            it == SmartPlaylistRuleField.COMPOSER ||
                            it == SmartPlaylistRuleField.FILE_PATH ||
                            it == SmartPlaylistRuleField.FOLDER_PATH
                    }) withContext(Dispatchers.IO) {
                        library.tracks.map { it.recordingId }.chunked(400).flatMap { ids ->
                            catalogDao.providerScopedTagRows(providerKey, ids)
                        }.groupBy { it.recordingId }
                    } else null
                    CanonicalSongFilterState.Ready(withContext(Dispatchers.Default) {
                        filterCanonicalBrowseSongs(
                            library.tracks, selected, providerKey,
                            scopedTagRowsByRecording = tagRows,
                        )
                    })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CanonicalSongFilterState.Failed
        }
    }
    val displayedTracks = (filteredState as? CanonicalSongFilterState.Ready)?.tracks.orEmpty()

    val entityState by produceState<CanonicalEntityBrowseState>(
        initialValue = CanonicalEntityBrowseState.Loading,
        key1 = providerKey, key2 = selectedContext,
        key3 = (selectedFilterId to selected to filtersUnavailable) to
            (Triple(sources, selectedSource, eligibleTracks) to libraryQuery),
    ) {
        if (selectedContext == CanonicalSmartFilterContext.SONGS) return@produceState
        catalogDb.invalidationTracker.createFlow(
            "catalog_media_asset", "catalog_release", "catalog_release_track",
            "catalog_recording_artist_credit", "catalog_release_artist_credit",
            "catalog_artist", "musicbrainz_release_enrichment",
            "catalog_external_identity",
        ).collectLatest {
            value = try {
                when {
                    filtersUnavailable && selectedFilterId != null ->
                        CanonicalEntityBrowseState.Failed
                    selectedFilterId != null && selected == null ->
                        CanonicalEntityBrowseState.Failed
                    selectedContext == CanonicalSmartFilterContext.ALBUMS ->
                        CanonicalEntityBrowseState.Albums(withContext(Dispatchers.IO) {
                            val keys = if (unified) sources.filter {
                                selectedSource == null || selectedSource == it
                            } else listOf(providerKey)
                            val all = keys.flatMap { key ->
                                if (selected == null || unified) catalogDao.providerScopedReleases(key)
                                else contextualReader.albums(key, selected)
                            }.distinctBy { it.id }
                            val ratedIds = if (unified && selectedStars != null) {
                                eligibleTracks.map { it.recordingId }.chunked(400)
                                    .flatMap { catalogDao.releaseIdsForVisibleRecordings(it) }.toSet()
                            } else null
                            all.filter { ratedIds == null || it.id in ratedIds }
                                .filter { !unified || libraryQuery.isBlank() ||
                                    it.title.contains(libraryQuery, ignoreCase = true) }
                                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        })
                    else -> CanonicalEntityBrowseState.Artists(withContext(Dispatchers.IO) {
                        val keys = if (unified) sources.filter {
                            selectedSource == null || selectedSource == it
                        } else listOf(providerKey)
                        val canonical = keys.flatMap { key ->
                            if (selected == null || unified) catalogDao.providerScopedArtists(key)
                            else contextualReader.artists(key, selected)
                        }.distinctBy { it.id }
                        val ratedIds = if (unified && selectedStars != null) {
                            eligibleTracks.map { it.recordingId }.chunked(400)
                                .flatMap { catalogDao.artistIdsForVisibleRecordings(it) }.toSet()
                        } else null
                        val credited = canonical.filter { ratedIds == null || it.id in ratedIds }
                            .map { CanonicalSelectedEntity(it.id, it.name) }
                        // Uncredited provider recordings remain discoverable
                        // via the SAME browse-only display groups used by the
                        // native playlist picker. No fabricated ArtistId.
                        val displayOnly = if (!unified) emptyList() else {
                            val creditedNames = credited.mapTo(HashSet()) {
                                it.name.lowercase()
                            }
                            canonicalLibraryDisplayArtistNames(
                                eligibleTracks, creditedNames,
                            ).map { name ->
                                CanonicalSelectedEntity(playlistDisplayArtistGroupId(name), name)
                            }
                        }
                        (credited + displayOnly).distinctBy { it.id }
                            .filter { !unified || libraryQuery.isBlank() ||
                                it.name.contains(libraryQuery, ignoreCase = true) }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CanonicalEntityBrowseState.Failed
            }
        }
    }
    val detailState by produceState<CanonicalDetailBrowseState>(
        initialValue = CanonicalDetailBrowseState.Loading,
        key1 = providerKey, key2 = selectedContext,
        key3 = Triple(selectedEntity, sources, selectedSource),
    ) {
        val entity = selectedEntity ?: return@produceState
        catalogDb.invalidationTracker.createFlow(
            "catalog_media_asset", "catalog_release_track",
            "catalog_recording_artist_credit", "catalog_release_artist_credit",
        ).collectLatest {
            value = try {
                val ids = withContext(Dispatchers.IO) {
                    val keys = if (unified) sources.filter {
                        selectedSource == null || selectedSource == it
                    } else listOf(providerKey)
                    keys.flatMap { key ->
                        when (selectedContext) {
                            CanonicalSmartFilterContext.ALBUMS ->
                                catalogDao.providerScopedReleaseRecordingIds(key, entity.id)
                            CanonicalSmartFilterContext.ARTISTS ->
                                playlistDisplayArtistNameFromGroupId(entity.id)?.let { name ->
                                    catalogDao.playlistDisplayArtistRecordingIds(key, name)
                                } ?: catalogDao.providerScopedArtistRecordingIds(key, entity.id)
                            CanonicalSmartFilterContext.SONGS -> emptyList()
                        }
                    }.distinct()
                }
                CanonicalDetailBrowseState.Ready(ids.toSet())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CanonicalDetailBrowseState.Failed
            }
        }
    }
    val scopedDetailTracks = when (val d = detailState) {
        is CanonicalDetailBrowseState.Ready -> eligibleTracks.filter { track ->
            track.recordingId in d.recordingIds && track.availableAssets.isNotEmpty()
        }
        else -> emptyList()
    }

    BackHandler(enabled = selectedEntity != null) { selectedEntity = null }
    BackHandler(enabled = selectedEntity == null, onBack = onClose)

    val playTrack: (CanonicalBrowseTrack) -> Unit = { track ->
        if (unified) {
            executeLibraryTrack(
                SmartCollectionActionSource.LibraryTrack(track.recordingId, selectedSource),
                CanonicalCollectionIntent.PLAY_NOW,
            )
        } else {
            track.availableAssets.firstOrNull { it.providerKey == providerKey }?.let { asset ->
                controller?.playCanonicalRemote(
                    request = CanonicalRemotePlaybackRequest(
                        recordingId = track.recordingId,
                        mediaAssetId = asset.mediaAssetId,
                        providerKey = providerKey,
                    ),
                    metadata = CanonicalRemotePlaybackMetadata(
                        title = track.title,
                        artistDisplay = track.artistDisplay,
                    ),
                )
            }
        }
    }

    val editRating: (CanonicalBrowseTrack) -> Unit = { track ->
        if (unified || track.availableAssets.any { it.providerKey == providerKey }) {
            editingRecordingId = track.recordingId
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .statusBarsPadding().navigationBarsPadding()
                .then(if (unified) Modifier.padding(
                    // Existing ListeningLibraryPage floating top-bar inset.
                    top = 72.dp,
                ) else Modifier),
        ) {
            if (!unified) RemoteBrowseTopBar(
                title = selectedEntity?.name ?: title,
                onBack = if (selectedEntity != null) { { selectedEntity = null } } else onClose,
            )
            if (selectedEntity != null) {
                Text(
                    selectedEntity?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 20.dp),
                )
                when (detailState) {
                    CanonicalDetailBrowseState.Loading ->
                        RemoteBrowseMessage("Carregando músicas vinculadas…")
                    CanonicalDetailBrowseState.Failed ->
                        RemoteBrowseMessage("Não foi possível confirmar as músicas deste item.")
                    is CanonicalDetailBrowseState.Ready -> {
                        RemoteBrowseSummary(songCount = scopedDetailTracks.size)
                        TrackList(
                            tracks = scopedDetailTracks,
                            artworkLoader = artworkLoader,
                            onTrackPlay = playTrack,                            onTrackEditRating = editRating,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                if (unified) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 20.dp),
                    )
                } else {
                    RemoteBrowseContextTabs(
                        selected = selectedContext,
                        onSelect = { selectedContext = it },
                    )
                }
                if (unified && searchVisible) SearchField(
                    query = libraryQuery,
                    onQueryChange = { libraryQuery = it },
                    onSubmit = {},
                    placeholder = "Pesquisar na biblioteca",
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
                )
                if (unified) NativeCanonicalLibraryFilters(
                    availableKeys = availableKeys,
                    selectedSource = selectedSource,
                    onSourceChange = { selectedSource = it },
                    selectedStars = selectedStars,
                    onStarsChange = { selectedStars = it },
                    requestToken = filterActionToken,
                )
                if (compatible.isNotEmpty() || selectedFilterId != null) {
                    RemoteSongSavedFilterMenu(
                        selectedName = selected?.name,
                        options = compatible,
                        selectedFilterId = selectedFilterId,
                        onSelect = { selectedFilterId = it },
                    )
                }
                if (filtersUnavailable && selectedFilterId == null) {
                    RemoteBrowseMessage("Filtros salvos indisponíveis.")
                }
                when (selectedContext) {
                    CanonicalSmartFilterContext.SONGS -> {
                        when (filteredState) {
                            CanonicalSongFilterState.Loading ->
                                RemoteBrowseMessage("Aplicando filtro…")
                            CanonicalSongFilterState.Failed ->
                                RemoteBrowseMessage(
                                    "Este filtro não pôde ser aplicado. Selecione Todas.",
                                )
                            is CanonicalSongFilterState.Ready -> {
                                RemoteBrowseSummary(songCount = displayedTracks.size)
                                TrackList(
                                    tracks = displayedTracks,
                                    artworkLoader = artworkLoader,
                                    onTrackPlay = playTrack,                                    onTrackEditRating = editRating,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    CanonicalSmartFilterContext.ALBUMS -> when (val state = entityState) {
                        CanonicalEntityBrowseState.Loading ->
                            RemoteBrowseMessage("Carregando álbuns canônicos…")
                        CanonicalEntityBrowseState.Failed ->
                            RemoteBrowseMessage("Não foi possível aplicar o filtro de álbuns.")
                        is CanonicalEntityBrowseState.Artists -> Unit
                        is CanonicalEntityBrowseState.Albums -> {
                            RemoteBrowseMessage("${state.releases.size} álbuns vinculados à fonte")
                            RemoteCanonicalEntityList(
                                entries = state.releases.map { entity ->
                                    Triple(
                                        CanonicalSelectedEntity(entity.id, entity.title),
                                        entity.artistDisplay.orEmpty(),
                                        entity.artworkUri,
                                    )
                                },
                                onSelect = { selectedEntity = it },
                                emptyText = "Nenhum álbum canônico disponível nesta fonte.",
                            )
                        }
                    }
                    CanonicalSmartFilterContext.ARTISTS -> when (val state = entityState) {
                        CanonicalEntityBrowseState.Loading ->
                            RemoteBrowseMessage("Carregando artistas canônicos…")
                        CanonicalEntityBrowseState.Failed ->
                            RemoteBrowseMessage("Não foi possível aplicar o filtro de artistas.")
                        is CanonicalEntityBrowseState.Albums -> Unit
                        is CanonicalEntityBrowseState.Artists -> {
                            RemoteBrowseMessage("${state.artists.size} artistas vinculados à fonte")
                            RemoteCanonicalEntityList(
                                entries = state.artists.map { artist ->
                                    Triple(artist, "", null)
                                },
                                onSelect = { selectedEntity = it },
                                emptyText = "Nenhum artista canônico disponível nesta fonte.",
                            )
                        }
                    }
                }
            }
        }
        editingRecordingId?.let { id ->
            CanonicalRecordingRatingDialog(
                recordingId = id,
                onDismiss = { editingRecordingId = null },
            )
        }
    }
}

/** Distinct entity types never share a synthetic RecordingId or metadata alias. */
@Composable
private fun RemoteBrowseContextTabs(
    selected: CanonicalSmartFilterContext,
    onSelect: (CanonicalSmartFilterContext) -> Unit,
) {
    // Reuse the very same native segmented tabs used by the local Library.
    SegmentedControl(
        options = listOf("Músicas", "Álbuns", "Artistas"),
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(CanonicalSmartFilterContext.entries[it]) },
        modifier = Modifier.padding(horizontal = PAGE_GUTTER),
    )
}

/**
 * Existing neutral outlined buttons and Settings rows, not another ad-hoc
 * Material3 filter menu. The picker holds real provider identities and
 * numeric star values; labels have no authority over song matching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeCanonicalLibraryFilters(
    availableKeys: List<String>,
    selectedSource: String?,
    onSourceChange: (String?) -> Unit,
    selectedStars: Int?,
    onStarsChange: (Int?) -> Unit,
    requestToken: Int,
) {
    // The native top-bar filter icon owns this single sheet. No duplicate
    // filter buttons or Material3 chips in the Library content itself.
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(requestToken) {
        if (requestToken > 0) open = true
    }
    if (!open) return

    ModalBottomSheet(
        onDismissRequest = { open = false },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Filtrar biblioteca",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
            Text(
                "Fonte de origem",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
            SettingsGroupSurface(modifier = Modifier.padding(horizontal = PAGE_GUTTER)) {
                (listOf<String?>(null) + availableKeys).forEach { key ->
                    val label = when {
                        key == null -> "Todas as fontes"
                        key == "local-mediastore" -> "Música local"
                        availableKeys.count { it.startsWith("opensubsonic:") } > 1 ->
                            "Navidrome " + (availableKeys.filter {
                                it.startsWith("opensubsonic:")
                            }.indexOf(key) + 1)
                        else -> "Navidrome"
                    }
                    SettingsRow(
                        title = label,
                        reserveIconSpace = false,
                        onClick = { onSourceChange(key); open = false },
                        trailing = {
                            if (selectedSource == key) Icon(
                                Icons.Rounded.Check, contentDescription = null,
                            )
                        },
                    )
                }
            }
            Text(
                "Avaliação das músicas",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
            SettingsGroupSurface(modifier = Modifier.padding(horizontal = PAGE_GUTTER)) {
                (listOf<Int?>(null) + (1..5).map { it }).forEach { value ->
                    SettingsRow(
                        title = value?.let { "$it estrelas" } ?: "Todas as avaliações",
                        reserveIconSpace = false,
                        onClick = { onStarsChange(value); open = false },
                        trailing = {
                            if (value != null) CanonicalRatingStars(
                                RatingDisplay(localValue = value.toDouble()),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (selectedStars == value) Icon(
                                Icons.Rounded.Check, contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteBrowseMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** Selection is by canonical ID; text is presentation only. */
@Composable
private fun RemoteCanonicalEntityList(
    entries: List<Triple<CanonicalSelectedEntity, String, String?>>,
    onSelect: (CanonicalSelectedEntity) -> Unit,
    emptyText: String,
) {
    if (entries.isEmpty()) {
        RemoteBrowseMessage(emptyText)
        return
    }
    // Identical artwork/title/subtitle geometry to the existing Library and
    // playlist list rows; never reproduce a second plain-text entity list.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        items(entries, key = { it.first.id }) { (entity, subtitle, artworkUri) ->
            LibraryRow(
                title = entity.name,
                subtitle = subtitle,
                imageUrl = artworkUri,
                onClick = { onSelect(entity) },
                artworkSize = 64.dp,
                horizontalPadding = PAGE_GUTTER,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
    }
}

/** Only fields supported by the selected canonical media context are offered. */
@Composable
private fun RemoteSongSavedFilterMenu(
    selectedName: String?,
    options: List<SavedSmartPlaylistV2Filter>,
    selectedFilterId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        TextButton(onClick = { expanded = true }) {
            Text("Filtro: " + (selectedName ?: if (selectedFilterId == null) "Todas" else "Indisponível"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Todas as músicas") },
                onClick = { expanded = false; onSelect(null) },
            )
            options.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(filter.name) },
                    onClick = { expanded = false; onSelect(filter.id) },
                )
            }
        }
    }
}

@Composable
private fun RemoteBrowseTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.remote_library_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun RemoteBrowseSummary(songCount: Int) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudDone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.remote_library_track_count, songCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.remote_library_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<CanonicalBrowseTrack>,
    artworkLoader: OpenSubsonicArtworkLoader,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        EmptyRemoteLibrary(modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
    ) {
        items(
            items = tracks,
            key = CanonicalBrowseTrack::recordingId,
        ) { track ->
            CanonicalTrackRow(
                track = track,
                artworkLoader = artworkLoader,
                onClick = { onTrackPlay(track) },
                onEditRating = { onTrackEditRating(track) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun CanonicalTrackRow(
    track: CanonicalBrowseTrack,
    artworkLoader: OpenSubsonicArtworkLoader,
    onClick: () -> Unit,
    onEditRating: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        RemoteTrackArtwork(
            mediaAssetId = track.availableAssets.firstOrNull()?.mediaAssetId,
            artworkRevision = track.artworkUri,
            title = track.title,
            artworkLoader = artworkLoader,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            canonicalLibraryVisibleRatings(
                track, LocalCanonicalRatingDisplay.current,
            ).forEach { rating ->
                CanonicalRatingStars(
                    rating,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            formatDuration(track.durationMs)?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CanonicalCatalogRatingMenu(onEdit = onEditRating)
    }
}

/**
 * Preserve main-branch rating menu alongside the canonical filter tabs.
 */
@Composable
internal fun CanonicalCatalogRatingMenu(onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = sharedStringResource(Res.string.canonical_rating_song_options),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(sharedStringResource(Res.string.recording_feedback_edit)) },
                leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
        }
    }
}

@Composable
internal fun RemoteTrackArtwork(
    mediaAssetId: String?,
    artworkRevision: String?,
    title: String,
    artworkLoader: OpenSubsonicArtworkLoader,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = mediaAssetId,
        key2 = artworkRevision,
        key3 = artworkLoader,
    ) {
        value = if (mediaAssetId == null || artworkRevision.isNullOrBlank() ||
            artworkRevision.startsWith("content://") || artworkRevision.startsWith("file://")) {
            null
        } else {
            when (val result = awaitRecoverableCanonicalArtwork(load = {
                artworkLoader.load(mediaAssetId)
            })) {
                is OpenSubsonicArtworkLoadResult.Ready -> withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)?.asImageBitmap()
                }
                is OpenSubsonicArtworkLoadResult.Placeholder -> null
            }
        }
    }

    // Local MediaStore sleeves use the native Coil path already used in the
    // playlist editor. A content URI is not an authenticated provider URL;
    // never feed remote provider artwork IDs to Coil.
    if (artworkRevision?.startsWith("content://") == true ||
        artworkRevision?.startsWith("file://") == true
    ) {
        AsyncImage(
            model = artworkRevision,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
        return
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        ArtworkPlaceholder(
            hasArtworkReference = !artworkRevision.isNullOrBlank(),
            modifier = modifier,
        )
    }
}

@Composable
private fun ArtworkPlaceholder(
    hasArtworkReference: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (hasArtworkReference) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun EmptyRemoteLibrary(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Text(
            text = stringResource(R.string.remote_library_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(durationMs: Long?): String? {
    val totalSeconds = durationMs?.takeIf { it > 0L }?.div(1_000L) ?: return null
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
