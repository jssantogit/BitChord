package com.music.bitchord.ui.screens

import android.graphics.BitmapFactory
import coil3.compose.AsyncImage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Album
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalEntitySortDateRow
import com.music.bitchord.data.catalog.UnifiedLocalCatalogIndex
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.NativeDetailTrackRowFrame
import com.music.bitchord.ui.components.NativeTrackRowText
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.Locale

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

private data class CanonicalSelectedEntity(
    val id: String,
    val name: String,
    val artworkUri: String? = null,
)

/** Only the three root Library categories support hold-to-select. */
internal fun canSelectCanonicalLibraryByLongPress(
    category: CanonicalSmartFilterContext,
    detailOpen: Boolean,
): Boolean = !detailOpen && category in setOf(
    CanonicalSmartFilterContext.SONGS,
    CanonicalSmartFilterContext.ALBUMS,
    CanonicalSmartFilterContext.ARTISTS,
)

private data class CanonicalLibraryMenuTarget(
    val selection: CanonicalLibrarySelection,
    val title: String,
    val subtitle: String,
    val imageUrl: String? = null,
)

/**
 * Offline-first browse for a provider's persisted canonical availability.
 *
 * Metadata remains Room-only. Artwork is the one bounded exception: rows hand
 * only their canonical mediaAssetId to a provider-owned loader which may serve
 * a credential-free disk cache or authenticate a cover-art request at runtime.
 * Authenticated URLs and provider cover IDs never enter Compose state.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    selectedSourceKey: String? = null,
    selectedStarsFilter: Int? = null,
    unifiedSort: LocalMusicSort = LocalMusicSort.TITLE_ASC,
    unifiedViewType: LibraryViewType = LibraryViewType.LIST,
    onSourceKeysChanged: (List<String>) -> Unit = {},
    selectionBarBottomPadding: Dp = 140.dp,
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
    val selectedSource = if (unified) selectedSourceKey else null
    var libraryQuery by remember(unified) { mutableStateOf("") }
    LaunchedEffect(searchVisible) { if (!searchVisible) libraryQuery = "" }
    val selectedStars = if (unified) selectedStarsFilter else null
    LaunchedEffect(unified) {
        if (unified) {
            try {
                UnifiedLocalCatalogIndex.ensure(context.applicationContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep remote rows visible if local media scan is unavailable.
            }
        }
    }
    LaunchedEffect(availableKeys) { if (unified) onSourceKeysChanged(availableKeys) }
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
    var libraryMenu by remember { mutableStateOf<CanonicalLibraryMenuTarget?>(null) }
    var playlistPickerSelection by remember {
        mutableStateOf<List<CanonicalLibrarySelection>?>(null)
    }
    // Selection is an explicit draft and survives source/star/sort changes.
    // Never reset it just because a browse filter or detail page changed.
    var selectedItems by remember { mutableStateOf<List<CanonicalLibrarySelection>>(emptyList()) }
    val batchSelectedSources = if (unified) selectedSource else providerKey
    // The count is resolved from canonical membership, not from the number
    // of artist/album cards tapped. Changes of source revalidate in-place.
    var batchResolved by remember(selectedItems, batchSelectedSources) {
        mutableStateOf<CanonicalLibraryResolvedSelection?>(null)
    }
    LaunchedEffect(selectedItems, batchSelectedSources) {
        if (selectedItems.isNotEmpty()) {
            batchResolved = try {
                withContext(Dispatchers.IO) {
                    canonicalLibraryResolveSelections(
                        context.applicationContext, selectedItems, batchSelectedSources,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
        }
    }
    var ratingDialogSelection by remember {
        mutableStateOf<List<CanonicalLibrarySelection>?>(null)
    }
    val libraryActionScope = rememberCoroutineScope()
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
    // A future physical smoke can distinguish unavailable MediaStore evidence
    // from an empty account, an invalid source choice and an active star filter.
    // Only counts and enum labels are exported (no account IDs or song paths).
    LaunchedEffect(
        unified, library.tracks.size, eligibleTracks.size, selectedContext,
        selectedSource, selectedStars, sources.size,
    ) {
        if (unified) runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "unified_library_browse_v1",
                metadata = mapOf(
                    "category" to selectedContext.name,
                    "recordingCount" to library.tracks.size.toString(),
                    "visibleCount" to eligibleTracks.size.toString(),
                    "localAssetCount" to library.tracks.sumOf { track ->
                        track.availableAssets.count { it.providerKey == "local-mediastore" }
                    }.toString(),
                    "remoteAssetCount" to library.tracks.sumOf { track ->
                        track.availableAssets.count { it.providerKey.startsWith("opensubsonic:") }
                    }.toString(),
                    "sourceCount" to sources.size.toString(),
                    "sourceMode" to when {
                        selectedSource == null -> "all"
                        selectedSource == "local-mediastore" -> "local"
                        else -> "remote"
                    },
                    "stars" to (selectedStars?.toString() ?: "all"),
                ),
            )
        }
    }
    var selectedEntity by remember(providerKey, selectedContext) {
        mutableStateOf<CanonicalSelectedEntity?>(null)
    }
    var detailQuery by remember(selectedEntity?.id) { mutableStateOf("") }
    var artistNestedReleaseOpen by remember { mutableStateOf(false) }
    var artistNestedBackToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedEntity?.id) { artistNestedReleaseOpen = false }
    LaunchedEffect(detailBackToken) {
        if (detailBackToken > 0) {
            if (artistNestedReleaseOpen) artistNestedBackToken++
            else selectedEntity = null
        }
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
    // One Room grouped read per active category/provider scope, not a
    // per-entity query loop. Unknown remote dates remain null.
    val nativeEntitySortDates by produceState(
        initialValue = emptyMap<String, CanonicalEntitySortDateRow>(),
        key1 = catalogDb,
        key2 = selectedContext,
        key3 = sources to selectedSource,
    ) {
        if (!unified || selectedContext == CanonicalSmartFilterContext.SONGS) return@produceState
        catalogDb.invalidationTracker.createFlow(
            "catalog_media_asset", "catalog_release_track",
            "catalog_recording_artist_credit", "catalog_release_artist_credit",
        ).collectLatest {
            value = try {
                val keys = sources.filter { selectedSource == null || selectedSource == it }
                withContext(Dispatchers.IO) {
                    when (selectedContext) {
                        CanonicalSmartFilterContext.ALBUMS -> catalogDao.unifiedReleaseSortDates(keys)
                        CanonicalSmartFilterContext.ARTISTS -> catalogDao.unifiedArtistSortDates(keys)
                        CanonicalSmartFilterContext.SONGS -> emptyList()
                    }.associateBy { it.entityId }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    // Resolve album/artist artwork by real canonical membership, never by
    // comparing an artist's display name with a recording's credit text.
    // These are grouped Room reads, not SQL calls from LazyColumn rows.
    val entityArtworkById by produceState<Map<String, CanonicalBrowseTrack>>(
        initialValue = emptyMap(),
        key1 = selectedContext,
        key2 = entityState,
        key3 = (sources to selectedSource) to eligibleTracks,
    ) {
        if (!unified || selectedContext == CanonicalSmartFilterContext.SONGS) return@produceState
        value = try {
            val keys = sources.filter { selectedSource == null || selectedSource == it }
            if (keys.isEmpty()) emptyMap() else {
                val coverLinks = withContext(Dispatchers.IO) {
                    when (val entities = entityState) {
                        is CanonicalEntityBrowseState.Albums ->
                            entities.releases.map { it.id }.chunked(300).flatMap { ids ->
                                catalogDao.playlistAlbumCoverRecordingIds(keys, ids)
                            }
                        is CanonicalEntityBrowseState.Artists -> {
                            val creditedIds = entities.artists.mapNotNull { artist ->
                                artist.id.takeIf {
                                    playlistDisplayArtistNameFromGroupId(it) == null
                                }
                            }
                            val displayIdByName = entities.artists.mapNotNull { artist ->
                                playlistDisplayArtistNameFromGroupId(artist.id)?.let { name ->
                                    name to artist.id
                                }
                            }.toMap()
                            val credited = creditedIds.chunked(300).flatMap { ids ->
                                catalogDao.playlistCreditedArtistCoverRecordingIds(keys, ids)
                            }
                            val display = displayIdByName.keys.toList().chunked(300)
                                .flatMap { names ->
                                    catalogDao.playlistDisplayArtistCoverRecordingIds(keys, names)
                                }.mapNotNull { match ->
                                    displayIdByName[match.groupId]?.let { groupId ->
                                        com.music.bitchord.data.catalog.CanonicalPlaylistAlbumCoverRow(
                                            groupId, match.recordingId,
                                        )
                                    }
                                }
                            credited + display
                        }
                        else -> emptyList()
                    }
                }
                canonicalLibraryEntityArtworkById(coverLinks, eligibleTracks).also { matched ->
                    // Counts only: a physical smoke can identify missing
                    // artwork links without logging any titles, IDs or URLs.
                    runCatching {
                        Diagnostics.event(
                            category = DiagnosticCategory.CATALOG,
                            name = "unified_library_artwork_v1",
                            metadata = mapOf(
                                "category" to selectedContext.name,
                                "coverLinkCount" to coverLinks.size.toString(),
                                "resolvedCount" to matched.size.toString(),
                                "eligibleTrackCount" to eligibleTracks.size.toString(),
                                "sourceMode" to when {
                                    selectedSource == null -> "all"
                                    selectedSource == "local-mediastore" -> "local"
                                    else -> "remote"
                                },
                            ),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching {
                Diagnostics.event(
                    category = DiagnosticCategory.CATALOG,
                    name = "unified_library_artwork_v1",
                    metadata = mapOf("category" to selectedContext.name, "phase" to "failed"),
                )
            }
            emptyMap()
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

    val toggleSelection: (CanonicalLibrarySelection) -> Unit = { target ->
        selectedItems = toggleCanonicalLibrarySelection(selectedItems, target)
    }
    val editRating: (CanonicalBrowseTrack) -> Unit = { track ->
        if (unified || track.availableAssets.any { it.providerKey == providerKey }) {
            editingRecordingId = track.recordingId
        }
    }
    val openTrackMenu: (CanonicalBrowseTrack) -> Unit = { track ->
        libraryMenu = CanonicalLibraryMenuTarget(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, track.recordingId),
            track.title, track.artistDisplay,
        )
    }
    val openAlbumMenu: (ReleaseEntity) -> Unit = { album ->
        libraryMenu = CanonicalLibraryMenuTarget(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, album.id),
            album.title, album.artistDisplay.orEmpty(), album.artworkUri,
        )
    }
    val openArtistMenu: (CanonicalSelectedEntity) -> Unit = { artist ->
        libraryMenu = CanonicalLibraryMenuTarget(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, artist.id),
            artist.name, "Artista",
        )
    }

    // All batch queue actions must revalidate the exact typed selection in
    // one canonical resolver; do not create a second per-source queue path.
    val queueMultiSelection: (CanonicalCollectionIntent) -> Unit = { intent ->
        val snapshot = selectedItems.toList()
        libraryActionScope.launch {
            val resolved = try {
                canonicalLibraryResolveSelections(
                    context.applicationContext, snapshot, selectedSource,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val ids = resolved?.recordingIds.orEmpty()
            if (resolved?.unavailableEntities == 0 && ids.isNotEmpty()) {
                executeLibraryTrack(
                    SmartCollectionActionSource.LibraryTracks(
                        ids, selectedSource, shuffle = false,
                    ),
                    intent,
                )
                // The user may have selected more items while this batch was
                // being revalidated. Never clear a newer selection draft.
                if (selectedItems == snapshot) selectedItems = emptyList()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
        if (unified) {
            // Selected albums/artists keep the exact native page chrome.
            // Only their revalidated RecordingId membership changes.
            val detail = selectedEntity
            val detailMatches = if (detail == null) emptyList() else scopedDetailTracks.filter {
                detailQuery.isBlank() ||
                    it.title.contains(detailQuery, ignoreCase = true) ||
                    it.artistDisplay.contains(detailQuery, ignoreCase = true)
            }
            val shownTracks = if (detail != null) detailMatches else
                if (selectedContext == CanonicalSmartFilterContext.SONGS)
                    displayedTracks else eligibleTracks
            val shownState = if (detail == null) filteredState else when (detailState) {
                CanonicalDetailBrowseState.Loading -> CanonicalSongFilterState.Loading
                CanonicalDetailBrowseState.Failed -> CanonicalSongFilterState.Failed
                is CanonicalDetailBrowseState.Ready -> CanonicalSongFilterState.Ready(detailMatches)
            }
            if (detail != null && selectedContext == CanonicalSmartFilterContext.ARTISTS) {
                // Real artist destination: the approved DetailScreen artist
                // chrome and sections, bound to proven canonical memberships.
                CanonicalNativeArtistDetail(
                    artistName = detail.name,
                    artistId = detail.id,
                    tracks = sortCanonicalUnifiedTracks(scopedDetailTracks, unifiedSort),
                    availableLibraryTracks = eligibleTracks,
                    sourceKeys = sources,
                    selectedSourceKey = selectedSource,
                    heroArtworkTrack = entityArtworkById[detail.id]
                        ?: scopedDetailTracks.firstOrNull(),
                    artworkLoader = artworkLoader,
                    query = detailQuery,
                    onQueryChange = { detailQuery = it },
                    searching = searchVisible,
                    nestedBackToken = artistNestedBackToken,
                    onNestedDetailChanged = { artistNestedReleaseOpen = it },
                    onTrackPlay = playTrack,
                    onTrackEditRating = editRating,
                    onTrackOptions = openTrackMenu,
                    onReleaseOptions = openAlbumMenu,
                    onRateArtist = {
                        // The same rating editor handles verified canonical
                        // ArtistIds and separately keyed display-artist groups.
                        ratingDialogSelection = listOf(CanonicalLibrarySelection(
                            CanonicalLibraryEntityType.ARTIST, detail.id,
                        ))
                    },
                    // Selection belongs to the root category, never the
                    // artist detail or its nested releases.
                    selectedItems = emptyList(),
                    onToggleSelection = {},
                    onPlayAll = { visible, shuffle ->
                        executeLibraryTrack(
                            SmartCollectionActionSource.LibraryTracks(
                                visible.map { it.recordingId }, selectedSource, shuffle,
                            ),
                            CanonicalCollectionIntent.PLAY_NOW,
                        )
                    },
                )
            } else NativeUnifiedLibraryBody(
                title = detail?.name ?: title,
                selectedContext = if (detail == null) selectedContext
                    else CanonicalSmartFilterContext.SONGS,
                tracks = sortCanonicalUnifiedTracks(shownTracks, unifiedSort),
                filteredState = shownState,
                entityState = entityState,
                artworkLoader = artworkLoader,
                query = if (detail == null) libraryQuery else detailQuery,
                onQueryChange = { if (detail == null) libraryQuery = it else detailQuery = it },
                searching = searchVisible,
                viewType = unifiedViewType,
                sort = unifiedSort,
                sortDates = nativeEntitySortDates,
                entityArtworkById = entityArtworkById,
                heroArtworkUri = detail?.artworkUri,
                heroArtworkTrack = detail?.let { entityArtworkById[it.id] }
                    ?: if (detail == null) null else scopedDetailTracks.firstOrNull(),
                isAlbumDetail = detail != null &&
                    selectedContext == CanonicalSmartFilterContext.ALBUMS,
                albumCredit = detail?.let { selected ->
                    (entityState as? CanonicalEntityBrowseState.Albums)
                        ?.releases?.firstOrNull { it.id == selected.id }?.artistDisplay
                }.orEmpty(),
                onTrackPlay = playTrack,
                onTrackEditRating = editRating,
                onTrackOptions = openTrackMenu,
                onAlbumOptions = openAlbumMenu,
                onArtistOptions = openArtistMenu,
                selectedItems = if (detail == null) selectedItems else emptyList(),
                canLongPressSelect = canSelectCanonicalLibraryByLongPress(
                    selectedContext, detailOpen = detail != null,
                ),
                onToggleSelection = toggleSelection,
                onPlayAll = { visible, shuffle ->
                    executeLibraryTrack(
                        SmartCollectionActionSource.LibraryTracks(
                            visible.map { it.recordingId }, selectedSource, shuffle,
                        ),
                        CanonicalCollectionIntent.PLAY_NOW,
                    )
                },
                onEntitySelect = { selectedEntity = it },
            )
        } else Column(
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
                        NativeLoadingSpinner()
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
                                NativeLoadingSpinner()
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
                            NativeLoadingSpinner()
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
                            NativeLoadingSpinner()
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
        libraryMenu?.let { menu ->
            val sourceForAction = if (unified) selectedSource else providerKey
            val art = when (menu.selection.type) {
                CanonicalLibraryEntityType.TRACK -> library.tracks.firstOrNull {
                    it.recordingId == menu.selection.entityId
                }
                else -> entityArtworkById[menu.selection.entityId]
            }
            val availableMenuActions = canonicalLibraryAvailableActions(
                type = menu.selection.type,
                hasCanonicalIdentity = menu.selection.type == CanonicalLibraryEntityType.TRACK ||
                    canonicalEntityRatingTarget(menu.selection) != null,
            )
            val queue: (CanonicalCollectionIntent) -> Unit = { intent ->
                libraryMenu = null
                libraryActionScope.launch {
                    val ids = try {
                        canonicalLibraryResolveRecordingIds(
                            context.applicationContext, listOf(menu.selection), sourceForAction,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { emptyList() }
                    if (ids.isNotEmpty()) executeLibraryTrack(
                        SmartCollectionActionSource.LibraryTracks(
                            ids, sourceForAction, shuffle = false,
                        ), intent,
                    )
                }
            }
            ModalBottomSheet(
                onDismissRequest = { libraryMenu = null },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                BrowseActionsSheet(
                    target = BrowseTarget(
                        browseId = null,
                        title = menu.title,
                        subtitle = menu.subtitle,
                        thumbnailUrl = menu.imageUrl,
                        type = when (menu.selection.type) {
                            CanonicalLibraryEntityType.TRACK -> BrowseType.OTHER
                            CanonicalLibraryEntityType.ALBUM -> BrowseType.ALBUM
                            CanonicalLibraryEntityType.ARTIST -> BrowseType.ARTIST
                        },
                        fromCard = false,
                    ),
                    onPlayNext = { queue(CanonicalCollectionIntent.ADD_NEXT) },
                    onAddToQueue = { queue(CanonicalCollectionIntent.ADD_TO_QUEUE) },
                    onAddToPlaylist = {
                        playlistPickerSelection = listOf(menu.selection)
                        libraryMenu = null
                    },
                    onSelect = if (selectedEntity == null) {
                        {
                            toggleSelection(menu.selection)
                            libraryMenu = null
                        }
                    } else null,
                    onEditRating = if (CanonicalLibraryAction.EDIT_RATING in availableMenuActions) {
                        {
                            if (menu.selection.type == CanonicalLibraryEntityType.TRACK) {
                                editingRecordingId = menu.selection.entityId
                            } else {
                                ratingDialogSelection = listOf(menu.selection)
                            }
                            libraryMenu = null
                        }
                    } else null,
                    headerArtwork = {
                        if (art != null) {
                            CanonicalUnifiedArtwork(art, artworkLoader, Modifier.fillMaxSize())
                        } else CoverPlaceholder(
                            if (menu.selection.type == CanonicalLibraryEntityType.ARTIST)
                                Icons.Rounded.MusicNote else Icons.Rounded.Album,
                            Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
        playlistPickerSelection?.let { selected ->
            CanonicalLibraryPlaylistPicker(
                selection = selected,
                selectedSource = if (unified) selectedSource else providerKey,
                onDismiss = { playlistPickerSelection = null },
            )
        }
        editingRecordingId?.let { id ->
            CanonicalRecordingRatingDialog(
                recordingId = id,
                onDismiss = { editingRecordingId = null },
            )
        }
        ratingDialogSelection?.let { ratings ->
            CanonicalLibraryEntityRatingDialog(
                targets = ratings,
                selectedSource = if (unified) selectedSource else providerKey,
                onDismiss = { ratingDialogSelection = null },
            )
        }
        // Keep the draft while navigating, but never float batch actions over
        // an artist or album detail page.
        if (unified && selectedEntity == null && selectedItems.isNotEmpty()) {
            CanonicalLibraryBatchActionBar(
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = PAGE_GUTTER, bottom = selectionBarBottomPadding),
                selectedEntityCount = selectedItems.size,
                summary = canonicalLibrarySelectionSummary(selectedItems.size, batchResolved),
                onClear = { selectedItems = emptyList() },
                onAddToPlaylist = { playlistPickerSelection = selectedItems.toList() },
                onRateSelection = if (selectedItems.all {
                    it.type == CanonicalLibraryEntityType.TRACK ||
                        canonicalEntityRatingTarget(it) != null
                }) { { ratingDialogSelection = selectedItems.toList() } } else null,
                onPlayNext = { queueMultiSelection(CanonicalCollectionIntent.ADD_NEXT) },
                onAddToQueue = { queueMultiSelection(CanonicalCollectionIntent.ADD_TO_QUEUE) },
            )
        }
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
    // A detail page removes this sheet from composition. On return, the
    // toolbar request counter is still positive; that OLD tap must not
    // reopen the filters. Only a new increment is a user request.
    var observedRequestToken by remember { androidx.compose.runtime.mutableIntStateOf(requestToken) }
    LaunchedEffect(requestToken) {
        if (requestToken != observedRequestToken) {
            observedRequestToken = requestToken
            if (requestToken > 0) open = true
        }
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
                        modifier = Modifier.testTag("library-source-" + (key ?: "all")),
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
internal fun NativeLoadingSpinner(modifier: Modifier = Modifier) {
    // Same first-party progress indicator already used by the playlists UI.
    // Loading is neither an empty-state sentence nor an error message.
    Box(
        modifier = modifier.fillMaxWidth().height(88.dp).testTag("native-library-loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
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
internal fun CanonicalTrackRow(
    track: CanonicalBrowseTrack,
    artworkLoader: OpenSubsonicArtworkLoader,
    onClick: () -> Unit,
    onEditRating: () -> Unit,
    onOptions: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    // Only the category's row should enter selection via a long press.
    // Detail pages never pass this callback.
    onLongPress: (() -> Unit)? = null,
    compactForArtist: Boolean = false,
    palette: ArtworkPalette? = null,
) {
    // Exactly the native SongRow frame (same horizontal inset and trailing
    // gutter), without converting RecordingId into a fake Song.videoId.
    NativeDetailTrackRowFrame(
        onClick = {
            if (selectionMode && onToggleSelection != null) onToggleSelection()
            else onClick()
        },
        onLongPress = onLongPress,
        onMore = onOptions,
        horizontalInset = if (compactForArtist) 0.dp else PAGE_GUTTER,
        modifier = if (compactForArtist) Modifier else Modifier.heightIn(min = 68.dp),
    ) {
        CanonicalUnifiedArtwork(
            track, artworkLoader, Modifier.size(if (compactForArtist) 48.dp else 52.dp),
        )
        Spacer(Modifier.width(if (compactForArtist) 12.dp else 14.dp))
        NativeTrackRowText(
            title = {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette?.onBackground ?: MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    text = track.artistDisplay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette?.onBackgroundVariant
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        Column(
            modifier = Modifier.padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            canonicalLibraryVisibleRatings(track, LocalCanonicalRatingDisplay.current)
                .forEach { rating ->
                    CanonicalRatingStars(
                        rating, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            if (!compactForArtist) formatDuration(track.durationMs)?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette?.onBackgroundVariant
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selectionMode && onToggleSelection != null) {
            IconButton(onClick = onToggleSelection, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle
                    else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "Desmarcar faixa" else "Selecionar faixa",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            CanonicalCatalogRatingMenu(onEdit = onEditRating, onOpenMenu = onOptions)
        }
    }
}

/**
 * Preserve main-branch rating menu alongside the canonical filter tabs.
 */
@Composable
internal fun CanonicalCatalogRatingMenu(
    onEdit: () -> Unit,
    onOpenMenu: (() -> Unit)? = null,
) {
    if (onOpenMenu != null) {
        // This is already the native canonical BrowseActionsSheet route.
        // Do not compose a second (dormant) rating-only dropdown beneath it.
        RowMoreButton(
            onClick = onOpenMenu,
            contentDescription = sharedStringResource(Res.string.canonical_rating_song_options),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        RowMoreButton(
            onClick = { expanded = true },
            contentDescription = sharedStringResource(Res.string.canonical_rating_song_options),
        )
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
            artworkRevision?.startsWith("content://") == true ||
            artworkRevision?.startsWith("file://") == true) {
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

/** Source-authenticated artwork must never be leaked to Coil's palette decoder. */
internal fun canonicalNativeReleasePaletteUri(
    primary: String?,
    fallback: String? = null,
): String? = sequenceOf(primary, fallback).filterNotNull().firstOrNull {
    it.startsWith("content://") || it.startsWith("file://")
}

/**
 * The unified catalog reuses the pre-regression release-page header, search,
 * action pills and Library rows. Its entities remain Recording/Release/Artist
 * IDs; this is intentionally not a conversion into fake local Song/browse IDs.
 */
@Composable
private fun NativeUnifiedLibraryBody(
    title: String,
    selectedContext: CanonicalSmartFilterContext,
    tracks: List<CanonicalBrowseTrack>,
    filteredState: CanonicalSongFilterState,
    entityState: CanonicalEntityBrowseState,
    artworkLoader: OpenSubsonicArtworkLoader,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    viewType: LibraryViewType,
    sort: LocalMusicSort,
    sortDates: Map<String, CanonicalEntitySortDateRow>,
    entityArtworkById: Map<String, CanonicalBrowseTrack>,
    heroArtworkUri: String? = null,
    heroArtworkTrack: CanonicalBrowseTrack? = null,
    isAlbumDetail: Boolean = false,
    albumCredit: String = "",
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
    onAlbumOptions: (ReleaseEntity) -> Unit,
    onArtistOptions: (CanonicalSelectedEntity) -> Unit,
    selectedItems: List<CanonicalLibrarySelection>,
    canLongPressSelect: Boolean,
    onToggleSelection: (CanonicalLibrarySelection) -> Unit,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
    onEntitySelect: (CanonicalSelectedEntity) -> Unit,
) {
    // A genuine release detail inherits the exact native DetailScreen
    // artwork palette. Browse categories keep the neutral Library surface.
    val palette = rememberArtworkPalette(
        imageUrl = if (isAlbumDetail) canonicalNativeReleasePaletteUri(
            heroArtworkUri, heroArtworkTrack?.artworkUri,
        ) else null,
    )
    val albumItems = (entityState as? CanonicalEntityBrowseState.Albums)?.releases.orEmpty()
    val artistItems = (entityState as? CanonicalEntityBrowseState.Artists)?.artists.orEmpty()
    // Browse-only artist fallback IDs are intentionally not real ArtistIds.
    // Their dates come from the same visible display groups, never a name
    // guess used to synthesize a canonical identity or queue item.
    val displayArtistDates = remember(artistItems, tracks) {
        val grouped = tracks.groupBy { it.artistDisplay.lowercase(Locale.ROOT) }
        artistItems.mapNotNull { artist ->
            val displayName = playlistDisplayArtistNameFromGroupId(artist.id)
                ?: return@mapNotNull null
            val members = grouped[displayName.lowercase(Locale.ROOT)].orEmpty()
            artist.id to CanonicalEntitySortDateRow(
                entityId = artist.id,
                dateAddedSeconds = members.mapNotNull { it.dateAddedSeconds }.maxOrNull(),
                dateModifiedSeconds = members.mapNotNull { it.dateModifiedSeconds }.maxOrNull(),
            )
        }.toMap()
    }
    val albums = remember(albumItems, sort, sortDates) {
        sortCanonicalUnifiedEntities(
            albumItems, sort, sortDates,
            id = { it.id }, title = { it.title },
        )
    }
    val artists = remember(artistItems, sort, sortDates, displayArtistDates) {
        sortCanonicalUnifiedEntities(
            artistItems, sort, sortDates + displayArtistDates,
            id = { it.id }, title = { it.name },
        )
    }
    val count = when (selectedContext) {
        CanonicalSmartFilterContext.SONGS -> tracks.size
        CanonicalSmartFilterContext.ALBUMS -> albums.size
        CanonicalSmartFilterContext.ARTISTS -> artists.size
    }
    val label = when (selectedContext) {
        CanonicalSmartFilterContext.SONGS ->
            nativeUnifiedTrackCountLabel(count, tracks.map { it.durationMs })
        CanonicalSmartFilterContext.ALBUMS -> "${count} álbuns"
        CanonicalSmartFilterContext.ARTISTS -> "${count} artistas"
    }
    // Drag selection belongs to the *root* Library only. Detail pages keep
    // their original touch/menu behavior; neither a global gesture interceptor
    // nor an additional browse surface is introduced.
    val dragOrder = remember(selectedContext, tracks, albums, artists) {
        when (selectedContext) {
            CanonicalSmartFilterContext.SONGS -> tracks.map {
                CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, it.recordingId)
            }
            CanonicalSmartFilterContext.ALBUMS -> albums.map {
                CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, it.id)
            }
            CanonicalSmartFilterContext.ARTISTS -> artists.map {
                CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, it.id)
            }
        }
    }
    val gridMode = viewType == LibraryViewType.GRID
    val dragRows = remember(dragOrder, gridMode) {
        canonicalLibraryDragRows(dragOrder, gridMode)
    }
    val dragIndices = remember(dragOrder) {
        dragOrder.withIndex().associate { it.value to it.index }
    }
    val listState = rememberLazyListState()
    val currentSelectedItems by rememberUpdatedState(selectedItems)
    val currentToggleSelection by rememberUpdatedState(onToggleSelection)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            if (isAlbumDetail) palette.background else MaterialTheme.colorScheme.background,
        ),
    ) {
        val sleeve = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
        val dragModifier = if (canLongPressSelect) {
            Modifier.pointerInput(dragRows, dragIndices, gridMode) {
                val visited = mutableSetOf<CanonicalLibrarySelection>()
                var previousIndex: Int? = null
                fun visit(position: androidx.compose.ui.geometry.Offset) {
                    val hitRow = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        position.y >= item.offset && position.y < item.offset + item.size
                    } ?: return
                    val target = canonicalLibraryDragHit(
                        dragRows, hitRow.key, position.x,
                        listState.layoutInfo.viewportSize.width.toFloat(), gridMode,
                    ) ?: return
                    val index = dragIndices[target] ?: return
                    val firstIndex = previousIndex ?: index
                    canonicalLibraryDragSpan(dragOrder, firstIndex, index).forEach { entry ->
                        // Paint-selection never deselects an item merely because the
                        // finger crosses it again. The user's existing draft survives.
                        if (visited.add(entry) && entry !in currentSelectedItems) {
                            currentToggleSelection(entry)
                        }
                    }
                    previousIndex = index
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        previousIndex = null
                        visited.clear()
                        visit(position)
                    },
                    onDragEnd = { previousIndex = null; visited.clear() },
                    onDragCancel = { previousIndex = null; visited.clear() },
                    onDrag = { change, _ ->
                        visit(change.position)
                        change.consume()
                    },
                )
            }
        } else Modifier
        LazyColumn(
            // Same insets as DetailScreen: the hero itself clears the frosted
            // toolbar. A second 72dp/status inset pushed the artwork too low.
            modifier = Modifier.fillMaxSize().then(dragModifier),
            state = listState,
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            item(key = "native-library-header") {
                NativeReleaseHeaderLayout(
                    title = title,
                    credit = if (isAlbumDetail) albumCredit else "",
                    meta = label,
                    palette = palette,
                    sleeveSize = sleeve,
                    artwork = {
                        when {
                            !heroArtworkUri.isNullOrBlank() &&
                                (heroArtworkUri.startsWith("content://") ||
                                    heroArtworkUri.startsWith("file://")) -> AsyncImage(
                                model = heroArtworkUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            heroArtworkTrack != null -> CanonicalUnifiedArtwork(
                                heroArtworkTrack, artworkLoader, Modifier.fillMaxSize(),
                            )
                            else -> CoverPlaceholder(Icons.Rounded.Folder, Modifier.fillMaxSize())
                        }
                    },
                    actions = {
                        if (count > 0 && tracks.isNotEmpty()) {
                            Spacer(Modifier.height(if (isAlbumDetail) 32.dp else 18.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ActionPill(
                                    icon = BitChordIcons.Play,
                                    label = stringResource(R.string.play),
                                    onClick = { onPlayAll(tracks, false) },
                                    filled = true,
                                    modifier = Modifier.weight(1f),
                                )
                                ActionPill(
                                    icon = BitChordIcons.Shuffle,
                                    label = stringResource(R.string.shuffle),
                                    onClick = { onPlayAll(tracks, true) },
                                    filled = false,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    },
                )
            }
            if (!isAlbumDetail || searching || query.isNotBlank()) {
                item(key = "native-library-search") {
                    DetailSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClose = { onQueryChange("") },
                    autoFocus = searching,
                    onFocused = {},
                    palette = palette,
                    )
                }
            }
            if (isAlbumDetail) item(key = "release-track-spacing") {
                Spacer(Modifier.height(20.dp))
            }
            when (selectedContext) {
                CanonicalSmartFilterContext.SONGS -> when (filteredState) {
                    CanonicalSongFilterState.Loading -> item {
                        NativeLoadingSpinner()
                    }
                    CanonicalSongFilterState.Failed -> item {
                        RemoteBrowseMessage("Não foi possível aplicar o filtro.")
                    }
                    is CanonicalSongFilterState.Ready -> if (tracks.isEmpty()) item {
                        RemoteBrowseMessage("Nenhuma música nesta seleção.")
                    } else if (viewType == LibraryViewType.GRID) {
                        gridRows(
                            tracks, 2, key = { it.recordingId },
                        ) { track ->
                            val target = CanonicalLibrarySelection(
                                CanonicalLibraryEntityType.TRACK, track.recordingId,
                            )
                            // Músicas uses the exact same native grid card
                            // as Álbuns/Artistas: artwork, type scale, spacing,
                            // overflow and selection all stay in one component.
                            NativeLibraryEntityCard(
                                title = track.title,
                                subtitle = track.artistDisplay,
                                imageUrl = null,
                                isArtist = false,
                                artwork = {
                                    CanonicalUnifiedArtwork(
                                        track, artworkLoader, Modifier.fillMaxSize(),
                                    )
                                },
                                onClick = {
                                    if (selectedItems.isNotEmpty()) onToggleSelection(target)
                                    else onTrackPlay(track)
                                },
                                onMore = { onTrackOptions(track) },
                                onLongPress = null, // The list-level gesture handles hold and drag.
                                selectionMode = selectedItems.isNotEmpty(),
                                selected = target in selectedItems,
                                onToggleSelection = { onToggleSelection(target) },
                            )
                        }
                    } else items(
                        items = tracks,
                        key = CanonicalBrowseTrack::recordingId,
                    ) { track ->
                        val target = CanonicalLibrarySelection(
                            CanonicalLibraryEntityType.TRACK, track.recordingId,
                        )
                        CanonicalTrackRow(
                            track = track, artworkLoader = artworkLoader,
                            onClick = { onTrackPlay(track) },
                            onEditRating = { onTrackEditRating(track) },
                            onOptions = { onTrackOptions(track) },
                            onLongPress = null, // The list-level gesture handles hold and drag.
                            selectionMode = selectedItems.isNotEmpty(),
                            selected = target in selectedItems,
                            onToggleSelection = { onToggleSelection(target) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 84.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
                CanonicalSmartFilterContext.ALBUMS -> when (entityState) {
                    CanonicalEntityBrowseState.Loading -> item {
                        NativeLoadingSpinner()
                    }
                    CanonicalEntityBrowseState.Failed -> item {
                        RemoteBrowseMessage("Não foi possível carregar os álbuns.")
                    }
                    is CanonicalEntityBrowseState.Albums -> {
                        if (albums.isEmpty()) item {
                            RemoteBrowseMessage("Nenhum álbum nesta seleção.")
                        }
                        if (viewType == LibraryViewType.GRID) {
                            gridRows(albums, 2, key = { it.id }) { album ->
                                val art = entityArtworkById[album.id]
                                val target = CanonicalLibrarySelection(
                                    CanonicalLibraryEntityType.ALBUM, album.id,
                                )
                                NativeLibraryEntityCard(
                                    title = album.title,
                                    subtitle = album.artistDisplay.orEmpty(),
                                    imageUrl = if (art == null) album.artworkUri else null,
                                    isArtist = false,
                                    artwork = art?.let { candidate ->
                                        { CanonicalUnifiedArtwork(
                                            candidate, artworkLoader, Modifier.fillMaxSize(),
                                        ) }
                                    },
                                    onClick = {
                                        if (selectedItems.isNotEmpty()) onToggleSelection(target)
                                        else onEntitySelect(CanonicalSelectedEntity(
                                            album.id, album.title, album.artworkUri,
                                        ))
                                    },
                                    onMore = { onAlbumOptions(album) },
                                    onLongPress = null, // The list-level gesture handles hold and drag.
                                    selectionMode = selectedItems.isNotEmpty(),
                                    selected = target in selectedItems,
                                    onToggleSelection = { onToggleSelection(target) },
                                )
                            }
                        } else items(albums, key = { it.id }) { album ->
                            val art = entityArtworkById[album.id]
                            val target = CanonicalLibrarySelection(
                                CanonicalLibraryEntityType.ALBUM, album.id,
                            )
                            LibraryRow(
                                title = album.title,
                                subtitle = album.artistDisplay.orEmpty(),
                                imageUrl = if (art == null) album.artworkUri else null,
                                artwork = art?.let { candidate ->
                                    { CanonicalUnifiedArtwork(
                                        candidate, artworkLoader, Modifier.fillMaxSize(),
                                    ) }
                                },
                                onClick = {
                                    if (selectedItems.isNotEmpty()) onToggleSelection(target)
                                    else onEntitySelect(CanonicalSelectedEntity(
                                        album.id, album.title, album.artworkUri,
                                    ))
                                },
                                onLongPress = null, // The list-level gesture handles hold and drag.
                                trailing = {
                                    if (selectedItems.isNotEmpty()) {
                                        IconButton(onClick = { onToggleSelection(target) }) {
                                            Icon(
                                                if (target in selectedItems) Icons.Rounded.CheckCircle
                                                else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = "Selecionar álbum",
                                            )
                                        }
                                    } else RowMoreButton(onClick = { onAlbumOptions(album) })
                                },
                                artworkSize = 52.dp,
                                horizontalPadding = PAGE_GUTTER,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 84.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                    is CanonicalEntityBrowseState.Artists -> Unit
                }
                CanonicalSmartFilterContext.ARTISTS -> when (entityState) {
                    CanonicalEntityBrowseState.Loading -> item {
                        NativeLoadingSpinner()
                    }
                    CanonicalEntityBrowseState.Failed -> item {
                        RemoteBrowseMessage("Não foi possível carregar os artistas.")
                    }
                    is CanonicalEntityBrowseState.Artists -> {
                        if (artists.isEmpty()) item {
                            RemoteBrowseMessage("Nenhum artista nesta seleção.")
                        }
                        if (viewType == LibraryViewType.GRID) {
                            gridRows(artists, 2, key = { it.id }) { artist ->
                                val art = entityArtworkById[artist.id]
                                val target = CanonicalLibrarySelection(
                                    CanonicalLibraryEntityType.ARTIST, artist.id,
                                )
                                NativeLibraryEntityCard(
                                    title = artist.name,
                                    subtitle = "",
                                    imageUrl = null,
                                    isArtist = true,
                                    artwork = art?.let { candidate ->
                                        { CanonicalUnifiedArtwork(
                                            candidate, artworkLoader, Modifier.fillMaxSize(),
                                        ) }
                                    },
                                    onClick = {
                                        if (selectedItems.isNotEmpty()) onToggleSelection(target)
                                        else onEntitySelect(artist)
                                    },
                                    onMore = { onArtistOptions(artist) },
                                    onLongPress = null, // The list-level gesture handles hold and drag.
                                    selectionMode = selectedItems.isNotEmpty(),
                                    selected = target in selectedItems,
                                    onToggleSelection = { onToggleSelection(target) },
                                )
                            }
                        } else items(artists, key = { it.id }) { artist ->
                            val art = entityArtworkById[artist.id]
                            val target = CanonicalLibrarySelection(
                                CanonicalLibraryEntityType.ARTIST, artist.id,
                            )
                            LibraryRow(
                                title = artist.name,
                                subtitle = "",
                                imageUrl = null,
                                artwork = art?.let { candidate ->
                                    { CanonicalUnifiedArtwork(
                                        candidate, artworkLoader, Modifier.fillMaxSize(),
                                    ) }
                                },
                                artworkShape = CircleShape,
                                onClick = {
                                    if (selectedItems.isNotEmpty()) onToggleSelection(target)
                                    else onEntitySelect(artist)
                                },
                                onLongPress = null, // The list-level gesture handles hold and drag.
                                trailing = {
                                    if (selectedItems.isNotEmpty()) {
                                        IconButton(onClick = { onToggleSelection(target) }) {
                                            Icon(
                                                if (target in selectedItems) Icons.Rounded.CheckCircle
                                                else Icons.Rounded.RadioButtonUnchecked,
                                                contentDescription = "Selecionar artista",
                                            )
                                        }
                                    } else RowMoreButton(onClick = { onArtistOptions(artist) })
                                },
                                artworkSize = 52.dp,
                                horizontalPadding = PAGE_GUTTER,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 84.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                    is CanonicalEntityBrowseState.Albums -> Unit
                }
            }
        }
    }
}

/** Coil handles the verified on-device cover URI; authenticated remote artwork
 * remains inside the OpenSubsonic provider loader (never a raw URL in Compose).
 */
@Composable
internal fun CanonicalUnifiedArtwork(
    track: CanonicalBrowseTrack,
    loader: OpenSubsonicArtworkLoader,
    modifier: Modifier = Modifier,
) {
    val localArt = track.artworkUri?.takeIf {
        track.availableAssets.any { a -> a.providerKey == "local-mediastore" } &&
            (it.startsWith("content://") || it.startsWith("file://"))
    }
    if (localArt != null) {
        AsyncImage(
            model = localArt,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        RemoteTrackArtwork(
            mediaAssetId = track.availableAssets.firstOrNull {
                it.providerKey.startsWith("opensubsonic:")
            }?.mediaAssetId,
            artworkRevision = track.artworkUri,
            title = track.title,
            artworkLoader = loader,
            modifier = modifier,
        )
    }
}

/** Same cell spacing as LocalFolder's grid; artist artwork is circular. */
@Composable
internal fun NativeLibraryEntityCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isArtist: Boolean,
    onClick: () -> Unit,
    artwork: (@Composable () -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
) {
    val shape = if (isArtist) CircleShape else RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().longPressMenuClickable(
            onClick = onClick, onLongClick = onLongPress,
        ).padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)) {
            when {
                artwork != null -> artwork()
                imageUrl != null -> AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CoverPlaceholder(
                    if (isArtist) Icons.Rounded.MusicNote else Icons.Rounded.Album,
                    Modifier.fillMaxSize(),
                )
            }
            if (selectionMode && onToggleSelection != null) {
                IconButton(
                    onClick = onToggleSelection,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle
                        else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = "Selecionar ${title}",
                    )
                }
            } else if (onMore != null) {
                RowMoreButton(
                    onClick = onMore,
                    modifier = Modifier.align(Alignment.TopEnd),
                    contentDescription = "Opções de ${title}",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotBlank()) {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
