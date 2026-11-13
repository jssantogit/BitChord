package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.contextClick
import androidx.compose.ui.unit.Dp
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.music.bitchord.ui.components.longPressMenuClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.theme.appleMusicKeyColor
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.player.rememberPlaybackArtworkImageModel
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.LocalReduceAnimations
import com.music.bitchord.ui.components.GlobalMotionPolicy
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import java.util.Locale

/**
 * Search-only MusicBrainz identity. This model deliberately contains no Song,
 * videoId or playback URI; tapping a metadata match cannot start a YT stream.
 */
enum class CanonicalSearchKind { RECORDING, RELEASE_GROUP, RELEASE, ARTIST }

/**
 * Search artwork provenance: an inherited sleeve may be reconciled when a
 * verified group Front arrives. Null is deliberately treated as owned/unknown
 * and MUST NOT be overwritten based on a matching recording title or group.
 */
enum class CanonicalArtworkProvenance { INHERITED_RELEASE, INHERITED_GROUP }

data class CanonicalCatalogSearchRow(
    val musicBrainzId: String,
    val kind: CanonicalSearchKind,
    val title: String,
    val subtitle: String?,
    val hasCatalogedAsset: Boolean = false,
    val fromOfflineCache: Boolean = false,
    /** Edition-specific Track MBID disambiguates repeated recordings in a tracklist. */
    val trackMbid: String? = null,
    /** First release/edition date from MusicBrainz. Never synthesized from a local import. */
    val releaseDate: String? = null,
    /** Album, Single, EP, etc. Only MusicBrainz can supply this classification. */
    val releaseKind: String? = null,
    /** Cached authorized cover art; not an implied playable asset. */
    val artworkUri: String? = null,
    /** Verifiable scoped search relation returned by MusicBrainz arid, not a text match. */
    val artistScopeMbid: String? = null,
    /** Exact Release MBID explicitly listed by MusicBrainz for this recording. */
    val coverReleaseMbid: String? = null,
    /** UI decoration only: strong canonical MBID -> readable local MediaAsset. */
    val hasVerifiedLocalAsset: Boolean = false,
    /** Release -> ReleaseGroup MBID advertised by MusicBrainz; cover fallback only. */
    val coverGroupMbid: String? = null,
    /** Recording/release Track duration from MusicBrainz; never inferred from UI title. */
    val durationMs: Long? = null,
    /** Secondary MusicBrainz ReleaseGroup type evidence, never inferred from a title. */
    val secondaryTypes: String? = null,
    /** Only set on Recording art adopted from a known MusicBrainz edition or group. */
    val artworkProvenance: CanonicalArtworkProvenance? = null,
)

/**
 * Indexed Server result. A canonical recording may have one or several server
 * assets, but the UI receives only a verified opaque mediaAssetId/providerKey,
 * not the private provider's URL, track id or authentication credentials.
 */
data class CanonicalServerSearchRow(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val title: String,
    val artistDisplay: String,
    /** Trusted provider artwork, never an audio stream or provider-native key. */
    val artworkUri: String? = null,
)

/** One navigable step; its rows are metadata identities, not video IDs. */
data class CanonicalCatalogDetailPage(
    val title: String,
    val summary: String?,
    val rows: List<CanonicalCatalogSearchRow>,
    val artworkUri: String? = null,
    /** Next MusicBrainz browse offset, which must use returned row count. */
    val nextOffset: Int? = null,
    val totalCount: Int? = null,
)

/** A verified public playlist row is metadata, not a playable Song. */
data class CanonicalPublicPlaylistSearchRow(
    val mbid: String,
    val title: String,
    val creator: String,
    val artworkUri: String?,
)

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    currentSong: Song?,
    isPlaying: Boolean,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    /**
     * Holding an album or playlist hit rather than tapping it — the same menu
     * the shelves open, so a release found by searching can go on the queue
     * without a trip through its page.
     */
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    /** Long-press handler for typeahead rows — opens the song actions sheet. */
    onTypeaheadLongPress: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /**
     * Room above the search field. The phone's frosted bar floats over the top
     * of the tab, so the field has to clear it; the desktop has no such bar.
     */
    topPadding: Dp,
    /**
     * Whether the page draws its own search field. The desktop's sidebar has
     * the one search box the window needs, so the page there is results only.
     */
    showField: Boolean = true,
    /**
     * Whether the page is searching the device's Local Music folder instead of
     * YouTube. The phone's top bar flips this; the desktop never does.
     */
    searchingLibrary: Boolean = false,
    /** Whether the page is showing the enabled addons' combined results; see [addonResults]. */
    searchingAddons: Boolean = false,
    /** Public MusicBrainz discovery shows metadata-only rows with no play actions. */
    searchingCanonical: Boolean = false,
    canonicalResults: UiState<List<CanonicalCatalogSearchRow>>? = null,
    canonicalPublicPlaylists: UiState<List<CanonicalPublicPlaylistSearchRow>>? = null,
    onCanonicalPlaylistOpen: (CanonicalPublicPlaylistSearchRow) -> Unit = {},
    onCanonicalPlaylistLongPress: ((CanonicalPublicPlaylistSearchRow) -> Unit)? = null,
    canonicalDetail: UiState<CanonicalCatalogDetailPage>? = null,
    onCanonicalOpen: (CanonicalCatalogSearchRow) -> Unit = {},
    onCanonicalLongPress: ((CanonicalCatalogSearchRow) -> Unit)? = null,
    onCanonicalRecordingInfo: (CanonicalCatalogSearchRow) -> Unit = {},
    onCanonicalBack: () -> Unit = {},
    onCanonicalLoadMore: () -> Unit = {},
    canonicalLoadingMore: Boolean = false,
    onCanonicalSearchLoadMore: () -> Unit = {},
    canonicalSearchHasMore: Boolean = false,
    canonicalSearchLoadingMore: Boolean = false,
    /** Only called for MBIDs that have a cataloged asset; Playback revalidates. */
    onCanonicalPlay: (String) -> Unit = {},
    onCanonicalAddToQueue: (String) -> Unit = {},
    /** Canonical remote assets, not source-owned stream URLs. */
    serverResults: UiState<List<CanonicalServerSearchRow>>? = null,
    onServerPlay: (CanonicalServerSearchRow) -> Unit = {},
    /** The Addons source's tracks for the submitted query; null until one is submitted. */
    addonResults: UiState<List<Song>>? = null,
    /** Words searched in the Addons tab before, newest first; shown while the field is empty. */
    addonHistory: List<String> = emptyList(),
    onAddonHistoryClick: (String) -> Unit = {},
    onAddonHistoryRemove: (String) -> Unit = {},
    onAddonHistoryClear: () -> Unit = {},
    /** The Library source's tracks for [query]; null while nothing is typed. */
    libraryResults: UiState<List<Song>>? = null,
    /** Files on the device are a running order to play through, not a station seed. */
    onLibrarySongClick: (List<Song>, Int) -> Unit = onSongClick,
    /** An authorized addon song never inherits local MediaStore provenance. */
    onAddonSongClick: (List<Song>, Int) -> Unit = onLibrarySongClick,
    /**
     * The user-facing General / Server / Local switcher, full width at the head of the page. Up
     * only while the field is empty: once something is typed the page is
     * about the results, and the switcher would just push them down.
     */
    sourceSwitcher: (@Composable () -> Unit)? = null,
    /** Mirrors the actual ViewModel submit state; Local remains live while typing. */
    searchCommitted: Boolean = false,
    appleResultTabs: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Tapping the search tab from the nav bar sets focusRequested;
    // respond by focusing the field and opening the keyboard.
    LaunchedEffect(focusRequested) {
        if (focusRequested && showField) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onFocusHandled()
        }
    }
    // Search keeps one list state while its contents change. Reset it for each
    // new request so choosing a recent search cannot inherit the history's
    // previous scroll position (or a previous result page's position).
    LaunchedEffect(scrollResetTrigger) {
        if (scrollResetTrigger > 0) listState.scrollToItem(0)
    }
    // A non-empty suggestion list means the field is mid-edit — see
    // MainViewModel.suggestions. Nothing below it is worth showing while it is
    // up: the results are for whatever was searched before this edit began,
    // and so are the filter tabs above them.
    val suggesting = suggestions.isNotEmpty() && !searchingLibrary && !searchingAddons && !searchingCanonical
    // Live media results arrive from the parallel typeahead pipeline; show
    // them only while the user is still typing (suggestions visible), so they
    // appear as a dropdown beneath the text completions rather than floating
    // after the search has committed.
    val showTypeahead = typeaheadResults.isNotEmpty() && suggesting
    LaunchedEffect(listState, results, loadingMore, searchingLibrary, searchingAddons, searchingCanonical) {
        if (results !is UiState.Success || searchingLibrary || searchingAddons || searchingCanonical) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (!loadingMore && total > 0 && lastVisible >= total - 4) onLoadMore()
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        // Search field and filter tabs stay fixed at the top, outside the
        // scrolling list, so they're always reachable rather than scrolling
        // away with the results or recent searches beneath them.
        // The FrostedTopBar is visible on this tab (showing "Search"), so we
        // clear it fully — status bar inset + bar height + breathing gap — so
        // the search field sits cleanly below the bar instead of overlapping it.
        Column(modifier = Modifier.padding(top = topPadding)) {
            if (showField) {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSubmit = onSubmit,
                    focusRequester = focusRequester,
                    placeholder = if (searchingLibrary) {
                        stringResource(Res.string.search_library_hint)
                    } else if (searchingAddons) {
                        stringResource(Res.string.search_server_hint)
                    } else {
                        stringResource(Res.string.search_hint)
                    },
                    modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
                )
            }
            if (sourceSwitcher != null && !searchCommitted && query.isNotBlank()) {
                // Source origins remain selectable while a query is visible,
                // so changing General ↔ Server ↔ Local does not require the
                // user to clear and retype the same artist or release.
                Box(Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp)) {
                    sourceSwitcher()
                }
            }
            // The filters only mean something once there is a result set to narrow;
            // they stay up for an empty or failed search too, or picking a filter
            // that finds nothing would take away the control needed to leave it.
            // They are YouTube's categories, so the Library source has none.
            // Catalog searches have artist/album/recording types too. The
            // underlying source is metadata, not a separate user-facing tab.
            // YouTube-only video/playlist filters are never offered there.
            if ((if (appleResultTabs) searchCommitted else !suggesting) &&
                !searchingLibrary && !searchingAddons &&
                (!searchingCanonical || canonicalDetail == null) &&
                (results != null || (searchingCanonical && canonicalResults != null))
            ) {
                SearchFilterTabs(
                    appleStyle = appleResultTabs,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    available = if (searchingCanonical) listOf(
                        SearchFilter.ALL, SearchFilter.ARTISTS,
                        SearchFilter.ALBUMS, SearchFilter.SONGS,
                        SearchFilter.PLAYLISTS,
                    ) else SearchFilter.entries,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            when {
                searchingCanonical -> {
                    if (canonicalSearchEditing(query, searchCommitted, canonicalDetail != null)) {
                        // Only locally known text, not invented Apple/server completions:
                        // an explicit action for the typed query plus matching history.
                        val known = canonicalRecentSearchEntries(history)
                            .map { it.title }
                            .filter { it.startsWith(query, ignoreCase = true) &&
                                !it.equals(query, ignoreCase = true) }
                            .distinct()
                            .take(2)
                        item(key = "canonical:typed-query-suggestion") {
                            SuggestionRow(
                                term = query.trim(),
                                isQueryAction = true,
                                onFill = null,
                                onClick = {
                                    onSubmit()
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                            )
                        }
                        itemsIndexed(known, key = { _, term -> "canonical:completion:$term" }) { _, term ->
                            SuggestionRow(
                                term = term,
                                isQueryAction = false,
                                onFill = { onQueryChange(term) },
                                onClick = {
                                    onSuggestionClick(term)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                            )
                        }
                        // The ViewModel already resolves cached MusicBrainz
                        // artists/recordings while typing. Reuse those real
                        // identities without any new network requests.
                        val cachedMatches = (canonicalResults as? UiState.Success)
                            ?.data.orEmpty()
                        itemsIndexed(
                            canonicalRowsForQuery(
                                cachedMatches, query, SearchFilter.ALL,
                            ).take(5),
                            key = { _, row ->
                                "canonical:cached:" + row.kind + ":" +
                                    (row.trackMbid ?: row.musicBrainzId)
                            },
                        ) { _, row ->
                            val kind = when (row.kind) {
                                CanonicalSearchKind.RECORDING -> stringResource(Res.string.canonical_recording_kind)
                                CanonicalSearchKind.RELEASE_GROUP -> stringResource(Res.string.canonical_group_kind)
                                CanonicalSearchKind.RELEASE -> stringResource(Res.string.canonical_release_kind)
                                CanonicalSearchKind.ARTIST -> stringResource(Res.string.canonical_artist_kind)
                            }
                            NativeSearchResultRow(
                                row = canonicalNativeSearchRowUi(row, kind),
                                onLongPress = onCanonicalLongPress?.let { held -> { held(row) } },
                                onClick = when {
                                    row.kind != CanonicalSearchKind.RECORDING -> ({ onCanonicalOpen(row) })
                                    row.hasCatalogedAsset -> ({ onCanonicalPlay(row.musicBrainzId) })
                                    else -> null
                                },
                                onMoreActions = listOf(
                                    NativeSearchRowAction(stringResource(Res.string.apple_search_view_details)) {
                                        if (row.kind == CanonicalSearchKind.RECORDING)
                                            onCanonicalRecordingInfo(row)
                                        else onCanonicalOpen(row)
                                    },
                                ) + if (row.kind == CanonicalSearchKind.RECORDING && row.hasCatalogedAsset)
                                    listOf(NativeSearchRowAction(stringResource(Res.string.smart_collection_batch_queue)) {
                                        onCanonicalAddToQueue(row.musicBrainzId)
                                    }) else emptyList(),
                            )
                        }
                    }
                    if (query.isBlank() &&
                        canonicalResults == null && canonicalDetail == null
                    ) {
                    // Same recent-search row, clear/remove actions, and persisted
                    // list as the original Search; never route legacy video IDs
                    // through a MusicBrainz-only history entry.
                    val recent = canonicalRecentSearchEntries(history)
                    if (recent.isEmpty()) {
                        item {
                            MessageState(stringResource(Res.string.canonical_search_start))
                        }
                    } else {
                        recentSearches(
                            history = recent,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            onClick = onHistoryClick,
                            onRemove = onHistoryRemove,
                            onClear = onHistoryClear,
                        )
                    }
                } else if (canonicalSearchEditing(query, searchCommitted, canonicalDetail != null)) {
                    // The pre-submit surface above owns text suggestions. Do not
                    // append the generic catalog "start searching" hint beneath them.
                    // On submit, the existing result renderer takes over.
                } else if (filter == SearchFilter.PLAYLISTS && canonicalDetail == null) {
                    canonicalPublicPlaylistSearchResults(
                        canonicalPublicPlaylists, onCanonicalPlaylistOpen,
                        onCanonicalPlaylistLongPress,
                    )
                } else canonicalMusicBrainzResults(
                    query, canonicalResults, canonicalDetail, filter, onCanonicalOpen,
                    onCanonicalLongPress, onCanonicalBack,
                    onCanonicalLoadMore, canonicalLoadingMore,
                    onCanonicalSearchLoadMore, canonicalSearchHasMore,
                    canonicalSearchLoadingMore,
                    onCanonicalPlay, onCanonicalAddToQueue,
                    onCanonicalRecordingInfo, appleResultTabs,
                )
                }
                searchingAddons && serverSearchEditing(query, searchCommitted) -> {
                    // Server search is explicit; the backend has no verified
                    // live-suggestion API. The typed action and stored terms
                    // are the only pre-submit suggestions.
                    item(key = "server:typed-query-suggestion") {
                        SuggestionRow(
                            term = query.trim(),
                            isQueryAction = true,
                            onFill = null,
                            onClick = {
                                onSubmit()
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        )
                    }
                    val knownTerms = addonHistory
                        .filter { it.startsWith(query, ignoreCase = true) &&
                            !it.equals(query, ignoreCase = true) }
                        .distinct()
                        .take(2)
                    itemsIndexed(
                        knownTerms,
                        key = { _, term -> "server:known-completion:$term" },
                    ) { _, term ->
                        SuggestionRow(
                            term = term,
                            isQueryAction = false,
                            onFill = { onQueryChange(term) },
                            onClick = {
                                onAddonHistoryClick(term)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        )
                    }
                }
                searchingLibrary || searchingAddons -> if (
                    searchingLibrary ||
                    serverResults !is UiState.Success || serverResults.data.isEmpty() ||
                    addonResults !is UiState.Success || addonResults.data.isNotEmpty()
                ) librarySearchResults(
                    query = query.trim(),
                    results = if (searchingAddons) addonResults else libraryResults,
                    addons = searchingAddons,
                    addonHistory = addonHistory,
                    onAddonHistoryClick = onAddonHistoryClick,
                    onAddonHistoryRemove = onAddonHistoryRemove,
                    onAddonHistoryClear = onAddonHistoryClear,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = if (searchingAddons) onAddonSongClick else onLibrarySongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    appleStyle = appleResultTabs,
                )
                suggesting -> {
                    searchSuggestions(
                        suggestions = suggestions,
                        // Picking one is done typing, so the keyboard comes down
                        // with it and the results get the whole screen.
                        onClick = { term ->
                            onSuggestionClick(term)
                            focusManager.clearFocus()
                        },
                        onFill = onQueryChange,
                    )
                    if (showTypeahead) {
                        searchTypeaheadDropdown(
                            typeaheadResults = typeaheadResults,
                            onSongClick = { song -> onTopResultPlay(song) },
                            onSongLongPress = onTypeaheadLongPress,
                            onBrowseClick = { item ->
                                onBrowseClick(item)
                            },
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    }
                }
                results == null -> if (history.isEmpty()) {
                    // Even a legacy presentation fallback must not advertise
                    // the removed YouTube Music discovery backend on Android.
                    item { MessageState(stringResource(Res.string.canonical_search_start)) }
                } else {
                    recentSearches(
                        history = history,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onClick = onHistoryClick,
                        onRemove = onHistoryRemove,
                        onClear = onHistoryClear,
                    )
                }
                results is UiState.Loading -> songListSkeleton(circular = filter == SearchFilter.ARTISTS)
                results is UiState.Error -> item { MessageState(results.message) }
                results is UiState.Success -> {
                    val tracks = results.data
                        .mapNotNull { row -> when (row) {
                            is SearchResult.TopTrack -> row.song
                            is SearchResult.Track -> row.song
                            is SearchResult.Browse -> null
                            else -> null
                        } }
                    val isCurrentRow = playingRowMatcher(tracks, currentSong)
                    val topResult = results.data.filterIsInstance<SearchResult.TopTrack>().firstOrNull()
                    if (filter == SearchFilter.ALL && topResult != null) {
                        item(key = "search:top-result:${topResult.song.videoId}") {
                            TopResultCard(
                                song = topResult.song,
                                onPlay = { onTopResultPlay(topResult.song) },
                                onPlaylist = { onTopResultPlaylist(topResult.song) },
                                onLongPress = { onSongLongPress(topResult.song) },
                            )
                        }
                    }
                    searchSections(results.data, filter).forEach { section ->
                        if (!appleResultTabs) section.title?.let { title ->
                            item(key = "search-section:$title") {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(
                                        start = PAGE_GUTTER,
                                        end = PAGE_GUTTER,
                                        top = 16.dp,
                                        bottom = 6.dp,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        itemsIndexed(
                            items = section.rows,
                            key = { index, row ->
                                when (row) {
                                    is SearchResult.TopTrack -> "top_${row.song.videoId}_$index"
                                    is SearchResult.Track -> "track_${row.song.videoId}_$index"
                                    is SearchResult.Browse -> "browse_${row.item.browseId}_$index"
                                }
                            },
                        ) { index, row ->
                            when (row) {
                                is SearchResult.TopTrack -> Unit
                                is SearchResult.Track -> SongRow(
                                    song = row.song,
                                    onClick = {
                                        onSongClick(tracks, tracks.indexOf(row.song).coerceAtLeast(0))
                                    },
                                    onLongPress = { onSongLongPress(row.song) },
                                    onSwipeToQueue = { onSongSwipe(row.song) },
                                    isCurrent = isCurrentRow(row.song),
                                    isPlaying = isPlaying && isCurrentRow(row.song),
                                    searchPlayingStyle = true,
                                    activeTint = appleMusicKeyColor(),
                                )
                                is SearchResult.Browse -> BrowseRow(
                                    item = row.item,
                                    onClick = { onBrowseClick(row.item) },
                                    onLongPress = onBrowseLongPress?.let { { it(row.item) } },
                                )
                            }
                            if (!appleResultTabs && index < section.rows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    if (loadingMore) {
                        songListSkeleton(
                            count = 3,
                            keyPrefix = "skeleton:search:more",
                            circular = filter == SearchFilter.ARTISTS,
                        )
                    }
                }
            }
            if (!appleResultTabs && !searchingLibrary && query.isNotBlank() &&
                (filter == SearchFilter.ALL || filter == SearchFilter.SONGS) &&
                (!searchingCanonical || canonicalDetail == null) &&
                (searchingCanonical || (!searchingAddons && results != null))
            ) {
                // General contains both public metadata and authorized server
                // renditions. A server result uses the same canonical queue
                // contract, never a videoId derived from an MBID.
                canonicalServerSearchRows(serverResults, onServerPlay)
            }
            if (searchingAddons && query.isNotBlank()) {
                canonicalServerSearchRows(serverResults, onServerPlay, appleStyle = appleResultTabs)
            }
            if (!appleResultTabs && searchingCanonical && canonicalDetail == null &&
                (filter == SearchFilter.ALL || filter == SearchFilter.SONGS) &&
                (addonResults as? UiState.Success)?.data?.isNotEmpty() == true
            ) {
                librarySearchResults(
                    query = query.trim(),
                    results = addonResults,
                    addons = true,
                    addonHistory = emptyList(),
                    onAddonHistoryClick = {},
                    onAddonHistoryRemove = {},
                    onAddonHistoryClear = {},
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = onAddonSongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                )
            }
        }
    }
}

/**
 * The MusicBrainz catalog proves that a Recording/ReleaseGroup/Artist exists,
 * not that the user owns an audio rendition. Keep the UI strictly non-playable
 * until canonical availability resolution is wired for each Recording MBID.
 */
private fun LazyListScope.canonicalPublicPlaylistSearchResults(
    results: UiState<List<CanonicalPublicPlaylistSearchRow>>?,
    onOpen: (CanonicalPublicPlaylistSearchRow) -> Unit,
    onLongPress: ((CanonicalPublicPlaylistSearchRow) -> Unit)?,
) {
    when (results) {
        null -> item(key = "public-playlists:hint") {
            MessageState("Pesquise playlists públicas por nome.")
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "public-playlists:search")
        is UiState.Error -> item(key = "public-playlists:error") { MessageState(results.message) }
        is UiState.Success -> if (results.data.isEmpty()) {
            item(key = "public-playlists:empty") { MessageState("Nenhuma playlist pública encontrada.") }
        } else items(
            items = results.data,
            key = { "public-playlist:${it.mbid}" },
        ) { playlist ->
            NativeSearchResultRow(
                row = NativeSearchRowUi(
                    title = playlist.title,
                    subtitle = playlist.creator,
                    artworkUri = playlist.artworkUri,
                    artistPortrait = false,
                ),
                onClick = { onOpen(playlist) },
                onLongPress = onLongPress?.let { held -> { held(playlist) } },
                onMoreActions = listOf(
                    NativeSearchRowAction(stringResource(Res.string.apple_search_view_details)) {
                        onOpen(playlist)
                    },
                ),
            )
        }
    }
}

private fun LazyListScope.canonicalMusicBrainzResults(
    query: String,
    results: UiState<List<CanonicalCatalogSearchRow>>?,
    detail: UiState<CanonicalCatalogDetailPage>?,
    filter: SearchFilter,
    onOpen: (CanonicalCatalogSearchRow) -> Unit,
    onLongPress: ((CanonicalCatalogSearchRow) -> Unit)?,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    onSearchLoadMore: () -> Unit,
    searchHasMore: Boolean,
    searchLoadingMore: Boolean,
    onPlay: (String) -> Unit,
    onAddToQueue: (String) -> Unit,
    onRecordingInfo: (CanonicalCatalogSearchRow) -> Unit,
    appleStyle: Boolean,
) {
    if (detail != null) {
        item(key = "musicbrainz:navigation") {
            Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 12.dp)) {
                NativeSearchSecondaryAction(
                    label = stringResource(Res.string.canonical_back),
                    onClick = onBack,
                    icon = PlaylistEditorIcons.Back,
                )
                if (detail is UiState.Success) {
                    detail.data.artworkUri?.let { art ->
                        AsyncImage(
                            model = art,
                            contentDescription = stringResource(Res.string.canonical_cover_art),
                            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    Text(detail.data.title, style = MaterialTheme.typography.titleLarge)
                    detail.data.summary?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    // Filters narrow public search results, never an opened release's
    // tracklist. In an album drill-down the tracklist must stay complete.
    val rows = when (detail) {
        is UiState.Success -> UiState.Success(detail.data.rows)
        is UiState.Error -> UiState.Error(detail.message)
        UiState.Loading -> UiState.Loading
        null -> when (results) {
            is UiState.Success -> UiState.Success(canonicalRowsForQuery(results.data, query, filter))
            else -> results
        }
    }
    when (rows) {
        null -> item(key = "musicbrainz:hint") {
            MessageState(stringResource(Res.string.canonical_search_hint))
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "musicbrainz:search")
        is UiState.Error -> item(key = "musicbrainz:error") {
            MessageState(rows.message)
        }
        is UiState.Success -> if (rows.data.isEmpty()) {
            item(key = "musicbrainz:empty") { MessageState(stringResource(Res.string.canonical_empty_search)) }
        } else {
            // Exactly the same native category grouping as legacy Search:
            // Artists lead General, then Songs, then Albums. Switching a
            // filter removes the headers rather than duplicating that UI.
            canonicalSearchDisplaySections(rows.data, filter).forEach { section ->
                if (!appleStyle) section.first?.let { type ->
                    item(key = "musicbrainz:section:${type.name}") {
                        Text(
                            text = stringResource(when (type) {
                                SearchFilter.ARTISTS -> Res.string.artists
                                SearchFilter.SONGS -> Res.string.canonical_filter_songs
                                else -> Res.string.albums
                            }),
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER, end = PAGE_GUTTER,
                                top = 16.dp, bottom = 6.dp,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                itemsIndexed(
                items = section.second,
                key = { _, row -> "mb:${row.kind}:${row.trackMbid ?: row.musicBrainzId}" },
            ) { index, row ->
                val kind = when (row.kind) {
                    CanonicalSearchKind.RECORDING -> stringResource(Res.string.canonical_recording_kind)
                    CanonicalSearchKind.RELEASE_GROUP -> stringResource(Res.string.canonical_group_kind)
                    CanonicalSearchKind.RELEASE -> stringResource(Res.string.canonical_release_kind)
                    CanonicalSearchKind.ARTIST -> stringResource(Res.string.canonical_artist_kind)
                }
                // No BrowseItem or synthetic browseId is created merely to
                // display a canonical MusicBrainz row. The native layout takes
                // presentation data, and playback stays evidence-gated.
                NativeSearchResultRow(
                    row = canonicalNativeSearchRowUi(row, kind),
                    onLongPress = onLongPress?.let { held -> { held(row) } },
                    onClick = when {
                        row.kind != CanonicalSearchKind.RECORDING -> ({ onOpen(row) })
                        row.hasCatalogedAsset -> ({ onPlay(row.musicBrainzId) })
                        else -> null
                    },
                    onMoreActions = listOf(
                        NativeSearchRowAction(stringResource(Res.string.apple_search_view_details)) {
                            if (row.kind == CanonicalSearchKind.RECORDING) onRecordingInfo(row)
                            else onOpen(row)
                        },
                    ) + if (row.kind == CanonicalSearchKind.RECORDING &&
                        row.hasCatalogedAsset
                    ) listOf(
                        NativeSearchRowAction(stringResource(Res.string.smart_collection_batch_queue)) {
                            onAddToQueue(row.musicBrainzId)
                        },
                    ) else emptyList(),
                )
                if (!appleStyle && index < section.second.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                }
            }
            if (detail is UiState.Success && detail.data.nextOffset != null) {
                item(key = "musicbrainz:load-more") {
                    NativeSearchSecondaryAction(
                        label = stringResource(Res.string.canonical_more_editions),
                        onClick = onLoadMore,
                        enabled = !loadingMore,
                        loading = loadingMore,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                    )
                }
            }
        }
    }
    if (detail == null && results is UiState.Success && searchHasMore) {
        item(key = "musicbrainz:search:load-more") {
            NativeSearchSecondaryAction(
                label = stringResource(Res.string.canonical_more_results),
                onClick = onSearchLoadMore,
                enabled = !searchLoadingMore,
                loading = searchLoadingMore,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
            )
        }
    }
}

/** Pre-submit suggestions must not share space with a submitted result or its empty hint. */
fun canonicalSearchEditing(
    query: String,
    searchCommitted: Boolean,
    detailOpen: Boolean,
): Boolean = query.isNotBlank() && !searchCommitted && !detailOpen

/** No server lookup occurs until the user explicitly submits the query. */
fun serverSearchEditing(query: String, searchCommitted: Boolean): Boolean =
    query.isNotBlank() && !searchCommitted

/** Show only terms, not YouTube media IDs, in the canonical empty-query surface. */
fun canonicalRecentSearchEntries(history: List<SearchHistoryEntity>): List<SearchHistoryEntity> =
    history.filter { it.id.startsWith("q:") }

/**
 * Canonical metadata is adapted straight into the existing native search row.
 * This is presentation, not a claim of a playable asset or a YT browse route.
 */
internal fun canonicalNativeSearchRowUi(
    row: CanonicalCatalogSearchRow,
    kindLabel: String,
): NativeSearchRowUi {
    val subtitle = canonicalDisplaySubtitle(row, kindLabel).ifBlank {
        // A recording without credits is still a recording. Use the supplied
        // localized kind, not the old BrowseType.ALBUM fallback used by the
        // legacy search adapter.
        kindLabel.substringBefore('·').trim()
    }
    return NativeSearchRowUi(
        title = row.title,
        subtitle = subtitle,
        artworkUri = row.artworkUri.takeUnless {
            row.kind == CanonicalSearchKind.RELEASE_GROUP &&
                !canonicalVerifiedGroupType(row)
        },
        artistPortrait = row.kind == CanonicalSearchKind.ARTIST,
        downloadBadge = row.kind == CanonicalSearchKind.RECORDING &&
            row.hasVerifiedLocalAsset,
        durationLabel = if (row.kind == CanonicalSearchKind.RECORDING) {
            canonicalDurationLabel(row.durationMs)
        } else null,
    )
}

/**
 * Server result is already a source-scoped asset; its private URI stays in
 * the provider executor. No synthetic BrowseItem is needed for presentation.
 */
internal fun canonicalServerNativeSearchRowUi(
    row: CanonicalServerSearchRow,
): NativeSearchRowUi = NativeSearchRowUi(
    title = row.title,
    subtitle = row.artistDisplay.ifBlank { BrowseType.OTHER.name.lowercase(Locale.ROOT)
        .replaceFirstChar { it.uppercase(Locale.ROOT) } },
    artworkUri = row.artworkUri,
    artistPortrait = false,
)

/** Only positive, independently supplied release classification may create an album tile. */
private fun canonicalVerifiedGroupType(row: CanonicalCatalogSearchRow): Boolean =
    row.releaseKind?.trim()?.lowercase(Locale.ROOT) in setOf("album", "ep", "single")

/** Presentation-only grouping; never changes the underlying indexed identities. */
fun canonicalSearchDisplaySections(
    rows: List<CanonicalCatalogSearchRow>,
    filter: SearchFilter,
): List<Pair<SearchFilter?, List<CanonicalCatalogSearchRow>>> {
    if (filter != SearchFilter.ALL) return listOf(null to rows)
    return listOf(
        SearchFilter.ARTISTS to rows.filter { it.kind == CanonicalSearchKind.ARTIST },
        SearchFilter.SONGS to rows.filter { it.kind == CanonicalSearchKind.RECORDING },
        SearchFilter.ALBUMS to canonicalRowsForFilter(rows, SearchFilter.ALBUMS),
    ).filter { it.second.isNotEmpty() }
}

fun canonicalRowsForFilter(
    rows: List<CanonicalCatalogSearchRow>,
    filter: SearchFilter,
): List<CanonicalCatalogSearchRow> {
    val knownGroupIds = rows.filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
        .mapTo(HashSet()) { it.musicBrainzId }
    return rows.filter { row ->
    when (filter) {
        SearchFilter.ALL -> true
        SearchFilter.SONGS -> row.kind == CanonicalSearchKind.RECORDING
        SearchFilter.ALBUMS -> canonicalVerifiedGroupType(row) && when (row.kind) {
            CanonicalSearchKind.RELEASE_GROUP -> true
            CanonicalSearchKind.RELEASE -> row.coverGroupMbid !in knownGroupIds
            else -> false
        }
        SearchFilter.ARTISTS -> row.kind == CanonicalSearchKind.ARTIST
        SearchFilter.VIDEOS, SearchFilter.PLAYLISTS -> false
    }
    }
}

/**
 * Search relevance is a presentation policy, not an identity migration.
 * When an exact artist match exists, the General page leads with that artist
 * and with works actually credited to that artist, rather than unrelated
 * recordings containing the same word in their titles. Other MBIDs remain
 * distinct; no artist is merged or rewritten by name.
 */
fun canonicalRowsForQuery(
    rows: List<CanonicalCatalogSearchRow>,
    query: String,
    filter: SearchFilter,
): List<CanonicalCatalogSearchRow> {
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    val candidates = canonicalRowsForFilter(rows, filter)
        .distinctBy { it.kind to it.musicBrainzId }
    if (normalized.isBlank()) return candidates
    val exactArtists = candidates.filter {
        it.kind == CanonicalSearchKind.ARTIST &&
            it.title.equals(normalized, ignoreCase = true)
    }
    val identityScoped = if (filter == SearchFilter.ALL && exactArtists.isNotEmpty()) {
        // The General view highlights the best-ranked exact artist from the
        // MusicBrainz response. Namesakes remain independently accessible in
        // the Artists filter; never merge their distinct MBIDs in Room.
        val primaryArtist = exactArtists.first()
        candidates.filter { row ->
            (row.kind == CanonicalSearchKind.ARTIST &&
                row.musicBrainzId == primaryArtist.musicBrainzId) ||
                (row.kind != CanonicalSearchKind.ARTIST &&
                    (row.artistScopeMbid == primaryArtist.musicBrainzId ||
                        canonicalCreditsMentionArtist(row.subtitle, normalized)))
        }
    } else candidates
    return identityScoped.sortedWith(
        compareBy<CanonicalCatalogSearchRow> { row ->
            when {
                row.kind == CanonicalSearchKind.ARTIST &&
                    row.title.equals(normalized, ignoreCase = true) -> 0
                row.title.equals(normalized, ignoreCase = true) -> 1
                row.kind == CanonicalSearchKind.ARTIST -> 2
                row.title.startsWith(normalized, ignoreCase = true) -> 3
                canonicalCreditsMentionArtist(row.subtitle, normalized) -> 4
                else -> 5
            }
        }.thenBy { row ->
            when (row.kind) {
                CanonicalSearchKind.ARTIST -> 0
                CanonicalSearchKind.RELEASE_GROUP -> 1
                CanonicalSearchKind.RELEASE -> 2
                CanonicalSearchKind.RECORDING -> 3
            }
        },
    )
}

/** Credited-name boundary check; a text match is never an MBID equivalence. */
private fun canonicalCreditsMentionArtist(credit: String?, query: String): Boolean {
    if (credit.isNullOrBlank()) return false
    return credit.equals(query, ignoreCase = true) ||
        credit.startsWith("$query ·", ignoreCase = true) ||
        credit.startsWith("$query,", ignoreCase = true) ||
        credit.startsWith("$query &", ignoreCase = true) ||
        credit.startsWith("$query feat.", ignoreCase = true) ||
        credit.contains(" · $query", ignoreCase = true) ||
        credit.contains(", $query", ignoreCase = true) ||
        credit.contains(" & $query", ignoreCase = true)
}

/** User-facing text only; never advertise DB availability or metadata plumbing. */
fun canonicalDisplaySubtitle(row: CanonicalCatalogSearchRow, kindLabel: String): String =
    when (row.kind) {
        // Never expose "Recording", "metadata-only" or an internal fallback
        // when the artist credit is missing.
        CanonicalSearchKind.RECORDING -> row.subtitle.orEmpty()
        // Native label already exists in every locale; trim its explanatory
        // suffix rather than introducing a second, untranslated label.
        CanonicalSearchKind.ARTIST -> kindLabel.substringBefore('·').trim()
        CanonicalSearchKind.RELEASE_GROUP, CanonicalSearchKind.RELEASE ->
            row.releaseKind?.takeIf {
                it.isNotBlank() && !it.equals("Other", ignoreCase = true)
            } ?: row.subtitle?.substringBefore('·')?.trim()
                ?.takeUnless { it.equals("Other", ignoreCase = true) }.orEmpty()
    }

/** Searchable files provided by configured servers, with live access checked at play. */
private fun LazyListScope.canonicalServerSearchRows(
    rows: UiState<List<CanonicalServerSearchRow>>?,
    onPlay: (CanonicalServerSearchRow) -> Unit,
    appleStyle: Boolean = false,
) {
    val results = (rows as? UiState.Success)?.data.orEmpty()
    if (results.isEmpty()) return
    if (!appleStyle) item(key = "server:heading") {
        Text(
            text = stringResource(Res.string.canonical_server_heading),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 14.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    itemsIndexed(
        items = results,
        key = { _, row -> "server:${row.mediaAssetId}" },
    ) { _, row ->
        NativeSearchResultRow(
            row = canonicalServerNativeSearchRowUi(row),
            onClick = { onPlay(row) },
            onMoreActions = listOf(
                NativeSearchRowAction(stringResource(Res.string.play)) { onPlay(row) },
            ),
        )
        if (!appleStyle) HorizontalDivider(
            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private data class SearchSection(val title: String?, val rows: List<SearchResult>)

/** The unfiltered page is useful only when its mixed result types are readable at a glance. */
private fun searchSections(rows: List<SearchResult>, filter: SearchFilter): List<SearchSection> {
    if (filter != SearchFilter.ALL) return listOf(SearchSection(null, rows))
    return listOf(
        SearchSection("Songs", rows.filterIsInstance<SearchResult.Track>()),
        SearchSection("Artists", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.ARTIST }),
        SearchSection("Albums", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.ALBUM }),
        SearchSection("Playlists", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.PLAYLIST }),
        SearchSection("More", rows.filterIsInstance<SearchResult.Browse>().filter { it.item.type == BrowseType.OTHER }),
    ).filter { it.rows.isNotEmpty() }
}

/**
 * The Library source's page: the Local Music folder's tracks that match, as
 * plain song rows. An artist or album hit needs no row of its own — every
 * track under that name matches too, so they are already here.
 */
private fun LazyListScope.librarySearchResults(
    query: String,
    results: UiState<List<Song>>?,
    addons: Boolean,
    addonHistory: List<String>,
    onAddonHistoryClick: (String) -> Unit,
    onAddonHistoryRemove: (String) -> Unit,
    onAddonHistoryClear: () -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    appleStyle: Boolean = false,
) {
    when (results) {
        null -> if (addons && query.isEmpty() && addonHistory.isNotEmpty()) {
            recentAddonSearches(addonHistory, onAddonHistoryClick, onAddonHistoryRemove, onAddonHistoryClear)
        } else item(key = "library:empty") {
            MessageState(
                stringResource(if (addons) Res.string.search_server_empty else Res.string.search_library_empty),
            )
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "skeleton:search:library")
        is UiState.Error -> item(key = "library:error") { MessageState(results.message) }
        is UiState.Success -> if (results.data.isEmpty()) {
            item(key = "library:no-match") {
                MessageState(
                    if (addons) {
                        stringResource(Res.string.search_server_no_results, query)
                    } else {
                        stringResource(Res.string.search_library_no_results, query)
                    },
                )
            }
        } else {
            val tracks = results.data
            val isCurrentRow = playingRowMatcher(tracks, currentSong)
            itemsIndexed(tracks, key = { index, song -> "${if (addons) "addon" else "library"}_${song.videoId}_$index" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onSongClick(tracks, index) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    isCurrent = isCurrentRow(song),
                    isPlaying = isPlaying && isCurrentRow(song),
                    searchPlayingStyle = true,
                    activeTint = appleMusicKeyColor(),
                    showSourceQuality = addons,
                )
                if (!appleStyle && index < tracks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** The All response carries its highest-confidence music hit as a promoted card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultCard(
    song: Song,
    onPlay: () -> Unit,
    onPlaylist: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 18.dp, bottom = 8.dp)
            .longPressMenuClickable(onClick = onPlay, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        Text(
            text = "Top result",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = rememberPlaybackArtworkImageModel(song, ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowMoreButton(
                onClick = onLongPress,
                tint = MaterialTheme.colorScheme.onSurface,
                iconSize = 24.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NativeSearchSecondaryAction(
                label = stringResource(Res.string.play),
                onClick = onPlay,
                icon = BitChordIcons.Play,
            )
            NativeSearchSecondaryAction(
                label = stringResource(Res.string.playlist_action),
                onClick = onPlaylist,
                icon = PlaylistEditorIcons.Add,
            )
        }
    }
}

/**
 * What YouTube would complete the half-typed query to, in place of the results
 * while it is being typed.
 *
 * The first row is the text as typed, put there by the view model rather than
 * taken from YouTube's answer, so running exactly what was asked for is always
 * the nearest row to the keyboard rather than something the thumb has to aim
 * past.
 */
private fun LazyListScope.searchSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    // This is a list-level inset rather than padding hidden inside the first
    // row. It keeps the gap under the field stable even when that row changes
    // its text or icon treatment.
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    // Skip the echo of the typed text (element 0) — it's already visible in
    // the search field itself — and cap at N so the list stays compact above
    // the playable-media cards.
    itemsIndexed(suggestions.drop(1).take(3), key = { _, term -> "suggest:$term" }) { _, term ->
        SuggestionRow(
            term = term,
            isQueryAction = false,
            onFill = { onFill(term) },
            onClick = { onClick(term) },
        )
    }
}

/**
 * One typeahead row: tap the text to search it, or the arrow to put it in the
 * field and carry on typing — the pair YouTube, Google and every mobile
 * keyboard's own suggestion strip use, and the reason a longer completion
 * isn't a dead end when it's only nearly right.
 */
@Composable
private fun SuggestionRow(
    term: String,
    isQueryAction: Boolean,
    onFill: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = PAGE_GUTTER,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            // The first row is the deliberate action to search the exact text
            // in the field, not a server-provided completion. Naming it makes
            // the otherwise duplicated wording read as intentional.
            text = term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onFill != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.NorthWest,
                    contentDescription = stringResource(Res.string.recent_search_edit, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            // Match the arrow button's full touch target, not only its width.
            // A width-only spacer made the first row shorter than the ones
            // below, so its vertical rhythm looked visibly uneven.
            Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * Hybrid dropdown shown beneath text suggestions while typing: a horizontal
 * divider, then live media rows (cover art + title + subtitle) that play on
 * tap and open the song menu on long-press.
 */
private fun LazyListScope.searchTypeaheadDropdown(
    typeaheadResults: List<SearchResult>,
    onSongClick: (Song) -> Unit,
    onSongLongPress: ((Song) -> Unit)?,
    onBrowseClick: (BrowseItem) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    val isCurrentRow = playingRowMatcher(
        typeaheadResults.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song },
        currentSong,
    )
    item(key = "typeahead:divider") {
        HorizontalDivider(
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }) { result ->
        when (result) {
            is SearchResult.Track -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = isCurrentRow(result.song),
                isPlaying = isPlaying && isCurrentRow(result.song),
            )
            is SearchResult.Browse -> BrowseRow(
                item = result.item,
                onClick = { onBrowseClick(result.item) },
                onLongPress = onSongLongPress?.let { { /* browse long-press not applicable */ } },
            )
            is SearchResult.TopTrack -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = isCurrentRow(result.song),
                isPlaying = isPlaying && isCurrentRow(result.song),
            )
        }
    }
}

/**
 * A single media row inside the typeahead dropdown: 52dp cover art, title,
 * and artist/album subtitle. Tap plays the track; long-press opens the menu.
 */
@Composable
private fun TypeaheadSongRow(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            AsyncImage(
                model = rememberPlaybackArtworkImageModel(song, ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) appleMusicKeyColor() else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(song.artist).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What was searched for before, shown in place of the results while the field
 * is empty — the same spot Spotify and Apple Music put it, and the reason the
 * blank search page isn't just a sentence any more.
 */
private fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (SearchHistoryEntity) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = appleMusicKeyColor(),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "recent:${it.id}" }) { entity ->
        RecentSearchEntityRow(
            entity = entity,
            isCurrent = entity.entityType == EntityType.TRACK && entity.id == currentSong?.videoId,
            isPlaying = isPlaying,
            onClick = { onClick(entity) },
            onRemove = { onRemove(entity.id) },
        )
    }
}

/** The Addons tab's recents: just the words that were searched, no song behind them. */
private fun LazyListScope.recentAddonSearches(
    history: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "addon-recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = appleMusicKeyColor(),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "addon-recent:$it" }) { term ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(term) }
                .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = term,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onRemove(term) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.recent_search_remove, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Spotify-style entity row: square thumbnail, bold title, subtitle with type,
 * and a removal button. Tapping navigates to the entity or plays it directly.
 */
@Composable
private fun RecentSearchEntityRow(
    entity: SearchHistoryEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (entity.id.startsWith("q:")) {
                // Reuse the native search glyph for search terms, never a fake
                // missing track artwork or playback indicator.
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                AsyncImage(
                    model = entity.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .thumbnailBorder(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (isCurrent && isPlaying) {
                    SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entity.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) appleMusicKeyColor() else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A query is not a TRACK; do not display the bogus "Track" label.
            if (!entity.id.startsWith("q:")) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entity.subtitle.ifBlank { entity.entityType.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.recent_search_remove, entity.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Legacy BrowseItem is an adapter at the edge of old search sources.
 * Shared native result layout never receives browse IDs or source models.
 */
@Composable
private fun BrowseRow(
    item: BrowseItem,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    localDownloaded: Boolean = false,
    durationLabel: String? = null,
) {
    NativeSearchResultRow(
        row = NativeSearchRowUi(
            title = item.title,
            subtitle = item.subtitle.ifBlank {
                item.type.name.lowercase(Locale.ROOT)
                    .replaceFirstChar { it.uppercase(Locale.ROOT) }
            },
            artworkUri = item.thumbnailUrl,
            artistPortrait = item.type == BrowseType.ARTIST,
            downloadBadge = localDownloaded,
            durationLabel = durationLabel,
        ),
        onClick = onClick,
        onLongPress = onLongPress,
    )
}

/** Display-only duration from actual MusicBrainz recording evidence. */
fun canonicalDurationLabel(durationMs: Long?): String? {
    if (durationMs == null || durationMs < 1000L) return null
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60L, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected
 * one inverted. They scroll horizontally so a long label set never squeezes the
 * text, and the gutter padding sits inside the scroll so it scrolls with them.
 */
@Composable
private fun SearchFilterTabs(
    appleStyle: Boolean = false,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    available: List<SearchFilter> = SearchFilter.entries,
) {
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceAnimations.current
    val selectionDuration = GlobalMotionPolicy.duration(GlobalMotionPolicy.selectionTintMillis, reduceMotion)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = if (appleStyle) 0.dp else PAGE_GUTTER,
                vertical = if (appleStyle) 4.dp else 6.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        available.forEach { entry ->
            val selected = entry == filter
            if (appleStyle) {
                // Bitchord-only tint feedback. AM-DECOMP verifies selection
                // colors but does not establish a timing for the indicator.
                val textTint by animateColorAsState(
                    targetValue = if (selected) appleMusicKeyColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = selectionDuration),
                    label = "appleResultTabTextTint",
                )
                val lineTint by animateColorAsState(
                    targetValue = if (selected) appleMusicKeyColor() else Color.Transparent,
                    animationSpec = tween(durationMillis = selectionDuration),
                    label = "appleResultTabUnderlineTint",
                )
                Column(
                    // Horizontal scrolling supplies unbounded width; use intrinsic
                    // label width so the fillMaxWidth bar cannot collapse.
                    modifier = Modifier.width(IntrinsicSize.Max).clickable {
                        if (!selected) haptics.play(Haptic.Select)
                        onFilterChange(entry)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = (when (entry) {
                            SearchFilter.ALL -> stringResource(Res.string.apple_search_top_results)
                            SearchFilter.SONGS -> stringResource(Res.string.canonical_filter_songs)
                            SearchFilter.ALBUMS -> stringResource(Res.string.albums)
                            SearchFilter.ARTISTS -> stringResource(Res.string.artists)
                            SearchFilter.VIDEOS -> stringResource(Res.string.canonical_filter_videos)
                            SearchFilter.PLAYLISTS -> stringResource(Res.string.playlists)
                        }).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelLarge,
                        color = textTint,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .height(3.dp)
                            .testTag(if (selected) "apple-selected-result-indicator" else "apple-result-indicator")
                            .background(lineTint),
                    )
                }
            } else Box(
                modifier = Modifier
                    .clip(FILTER_PILL_SHAPE)
                    .background(
                        if (selected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // Only the pill that isn't already selected has anything to
                    // report — re-tapping the current filter changes nothing, so
                    // buzzing for it would be feedback for a no-op.
                    .clickable {
                        if (!selected) haptics.play(Haptic.Select)
                        onFilterChange(entry)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = when (entry) {
                        SearchFilter.ALL -> stringResource(Res.string.canonical_filter_all)
                        SearchFilter.SONGS -> stringResource(Res.string.canonical_filter_songs)
                        SearchFilter.ALBUMS -> stringResource(Res.string.albums)
                        SearchFilter.ARTISTS -> stringResource(Res.string.artists)
                        SearchFilter.VIDEOS -> stringResource(Res.string.canonical_filter_videos)
                        SearchFilter.PLAYLISTS -> stringResource(Res.string.playlists)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Root-search origins are a pre-submit step, just as Apple shows its catalog
 * selector before the category tabs. Bitchord must keep its third Server
 * source rather than mislabel MusicBrainz as an Apple Music subscription.
 */
@Composable
fun AppleSearchOriginTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val reduceMotion = LocalReduceAnimations.current
    val selectionDuration = GlobalMotionPolicy.duration(GlobalMotionPolicy.selectionTintMillis, reduceMotion)
    Row(
        modifier = Modifier.fillMaxWidth().then(
            if (options.size <= 2) Modifier else Modifier.horizontalScroll(rememberScrollState()),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val textTint by animateColorAsState(
                targetValue = if (selected) appleMusicKeyColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = selectionDuration),
                label = "appleOriginTabTextTint",
            )
            val lineTint by animateColorAsState(
                targetValue = if (selected) appleMusicKeyColor() else Color.Transparent,
                animationSpec = tween(durationMillis = selectionDuration),
                label = "appleOriginTabUnderlineTint",
            )
            Column(
                modifier = Modifier
                    .then(if (options.size <= 2) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max))
                    .clickable { if (!selected) onSelect(index) }
                    .semantics { this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.titleSmall,
                    color = textTint,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                )
                Box(
                    Modifier.fillMaxWidth()
                        .height(3.dp)
                        .testTag(if (selected) "apple-selected-origin-indicator" else "apple-origin-indicator")
                        .background(lineTint),
                )
            }
        }
    }
}

/** Rounded, but well short of a capsule — the corner reads as a cut, not a curve. */
/** Apple-style selected tabs are blue independently of the dynamic artwork theme. */

private val FILTER_PILL_SHAPE = RoundedCornerShape(12.dp)
