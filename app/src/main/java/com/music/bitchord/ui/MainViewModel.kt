package com.music.bitchord.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.R
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.auth.profileId
import com.music.bitchord.auth.sessionId
import com.music.bitchord.auth.adjacentProfile
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.catalog.allowedCanonicalLyricsSources
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtSize
import com.music.bitchord.data.catalog.metadata.MusicBrainzCoverArtCacheEntry
import com.music.bitchord.data.catalog.metadata.RoomMusicBrainzCoverArtStore
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistDiscographyPager
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzRecommendationFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityClient
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityEvidence
import com.music.bitchord.data.catalog.metadata.ListenBrainzPopularityFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzArtistTopFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideFetchResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzSitewideRange
import com.music.bitchord.data.catalog.metadata.MusicBrainzMetadataEnrichmentProvider
import com.music.bitchord.data.catalog.metadata.MetadataLookupResult
import com.music.bitchord.data.catalog.metadata.RecordingMetadataEnrichment
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRecording
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.ui.screens.CanonicalCatalogDetailPage
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalServerSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.selectSpotifyCanonicalAsset
import com.music.bitchord.data.spotify.spotifyCanonicalAssetAsSong
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.auth.CapturedSession
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.withoutRepeatsOf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.download.Downloads
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.BoundedContextRequests
import com.music.bitchord.playback.contextEntryKey
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.matchesSearch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the Search tab searches: YouTube Music, or the Local Music folder on this device. */
enum class SearchSource { YOUTUBE, LIBRARY, ADDONS, CANONICAL }

/** User-facing boundaries. Providers do not own separate search interfaces. */
internal enum class SearchOrigin { GENERAL, SERVER, LOCAL }

internal fun searchOriginOf(source: SearchSource): SearchOrigin = when (source) {
    SearchSource.YOUTUBE, SearchSource.CANONICAL -> SearchOrigin.GENERAL
    SearchSource.ADDONS -> SearchOrigin.SERVER
    SearchSource.LIBRARY -> SearchOrigin.LOCAL
}

internal fun searchSourceForOrigin(
    origin: SearchOrigin,
    canonicalMode: Boolean,
    configs: List<SourceConfig>,
): SearchSource = when (origin) {
    SearchOrigin.GENERAL -> SearchSource.CANONICAL
    SearchOrigin.SERVER -> SearchSource.ADDONS
    SearchOrigin.LOCAL -> SearchSource.LIBRARY
}

/**
 * Playback source opt-out is authoritative for search too. A disabled YT
 * provider must not be contacted by a legacy search tab selection, even if
 * the pre-migration preference or source config still enables YouTube.
 */
internal fun effectiveSearchSource(
    requested: SearchSource,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): SearchSource = if (requested == SearchSource.YOUTUBE) SearchSource.CANONICAL else requested

/** #208: discovery is canonical on new and upgraded installs, regardless of legacy opt-in. */
internal fun shouldUseCanonicalDiscovery(
    @Suppress("UNUSED_PARAMETER") explicitlyEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): Boolean = true

internal fun isYouTubeSourceEnabled(
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): Boolean = false

internal fun shouldPrefetchLegacyYouTubeAccountData(
    @Suppress("UNUSED_PARAMETER") signedIn: Boolean,
    @Suppress("UNUSED_PARAMETER") canonicalDiscoveryEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): Boolean = false

internal fun defaultDiscoverySearchSource(
    @Suppress("UNUSED_PARAMETER") explicitlyEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): SearchSource = SearchSource.CANONICAL

internal enum class LegacyDiscoveryRequest {
    HOME,
    HOME_SUPPLEMENT,
    RECENTLY_PLAYED,
    MOODS_AND_GENRES,
    MOOD_GENRE_ARTWORK,
    MOOD_GENRE_SHELVES,
}

internal fun shouldRunLegacyYoutubeVersionLookup(
    @Suppress("UNUSED_PARAMETER") canonicalMode: Boolean,
    @Suppress("UNUSED_PARAMETER") configs: List<SourceConfig>,
): Boolean = false

/**
 * Compatibility only for an explicitly opened YouTube link. Unlike
 * background discovery it is not an implicit YouTube lookup, and cannot
 * turn a provider browse/video ID into canonical musical identity.
 * Remove after the link-migration contract is implemented.
 */
internal fun shouldHandleExplicitLegacyYoutubeLink(configs: List<SourceConfig>): Boolean =
    isYouTubeSourceEnabled(configs)

/**
 * The retired YouTube backend has been deleted. A stale discovery-mode flag
 * must never turn a previously supplied closure into a network request.
 */
internal class LegacyDiscoveryRequestGate(
    @Suppress("UNUSED_PARAMETER") useCanonicalDiscovery: Boolean,
) {
    suspend fun <T> run(
        @Suppress("UNUSED_PARAMETER") request: LegacyDiscoveryRequest,
        @Suppress("UNUSED_PARAMETER") legacyRequest: suspend () -> T,
    ): T? = null
}

/** Historical Spotify→YouTube matching is unavailable on Android. */
internal suspend fun <T> runLegacySpotifyMatchIfAllowed(
    @Suppress("UNUSED_PARAMETER") allowed: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") resolve: suspend () -> T?,
): T? = null

/** An old enabled flag cannot initiate a removed YouTube browse endpoint. */
internal suspend fun <T> runLegacyYoutubeBrowseIfEnabled(
    @Suppress("UNUSED_PARAMETER") enabled: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") request: suspend () -> T,
): T? = null

/** A monotonically increasing token makes uncancellable old responses harmless. */
internal class DiscoveryRequestEpoch {
    private val current = AtomicLong(0L)

    fun next(): Long = current.incrementAndGet()

    fun current(): Long = current.get()

    fun invalidateAndNext(): Long = current.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = generation == current.get()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val authStore = AuthStore(app)

    // Process-wide, so the playback service offers Like on the same tracks.
    private val _signedIn = SignedIn.state(app)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    /**
     * Token for the next page of Home shelves; null once there's nothing
     * more. Declared here rather than by [loadMoreHome] because [init] calls
     * [loadHome] synchronously up to its first suspension point — a property
     * declared after [init] would still be null when that runs.
     */
    private var homeContinuation: String? = null

    /** Titles already on screen, so a later page can't repeat a shelf. */
    private val homeSeenTitles = mutableSetOf<String>()

    private val _homeLoadingMore = MutableStateFlow(false)

    /**
     * Requests still filling out the first Play page. The core feed and each
     * supplement browse run in parallel and publish as they land, so the list
     * goes Success while several shelves are still on the wire — counted here
     * so the feed can say more is coming instead of just ending mid-load.
     */
    private val _homePendingShelves = MutableStateFlow(0)

    /** True whenever shelves are still due at the end of the feed, from either source. */
    val homeLoadingMore: StateFlow<Boolean> =
        combine(_homeLoadingMore, _homePendingShelves) { paging, pending -> paging || pending > 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _homeRecentlyPlayedLoading = MutableStateFlow(false)
    val homeRecentlyPlayedLoading: StateFlow<Boolean> = _homeRecentlyPlayedLoading.asStateFlow()
    private val homeLoadGeneration = DiscoveryRequestEpoch()
    private var homeLoadJob: Job? = null
    private var homePagingJob: Job? = null
    private var homeRefreshJob: Job? = null

    private val _explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = _explore.asStateFlow()
    private val exploreLoadGeneration = DiscoveryRequestEpoch()
    private var exploreLoadJob: Job? = null
    private var exploreRefreshJob: Job? = null
    private var moodGenreRequestJob: Job? = null
    private var moodGenreArtworkJob: Job? = null
    private val _usesCanonicalDiscovery = MutableStateFlow(isCanonicalDiscoverySelected())
    val usesCanonicalDiscovery: StateFlow<Boolean> = _usesCanonicalDiscovery.asStateFlow()
    private val _canonicalDiscoveryCatalog = MutableStateFlow<UiState<List<CanonicalCatalogSearchRow>>>(
        UiState.Loading,
    )
    val canonicalDiscoveryCatalog: StateFlow<UiState<List<CanonicalCatalogSearchRow>>> =
        _canonicalDiscoveryCatalog.asStateFlow()
    /** Only verified local file URIs; never any legacy YouTube video handles. */
    private val _canonicalHomeLocalPicks = MutableStateFlow<List<Song>>(emptyList())
    val canonicalHomeLocalPicks: StateFlow<List<Song>> = _canonicalHomeLocalPicks.asStateFlow()
    /** Read-only, synced OpenSubsonic/Jellyfin media assets for the native Home shelf. */
    private val _canonicalHomeRemotePicks =
        MutableStateFlow<List<CanonicalServerSearchRow>>(emptyList())
    val canonicalHomeRemotePicks: StateFlow<List<CanonicalServerSearchRow>> =
        _canonicalHomeRemotePicks.asStateFlow()

    /** Ranked by actual recent local listening starts, not public MusicBrainz popularity. */
    private val _canonicalTrendingRecordingMbids = MutableStateFlow<List<String>>(emptyList())
    val canonicalTrendingRecordingMbids: StateFlow<List<String>> =
        _canonicalTrendingRecordingMbids.asStateFlow()

    /** Local top plays from real Listening events joined to MediaStore URIs. */
    private val _canonicalTrendingLocalSongs = MutableStateFlow<List<Song>>(emptyList())
    val canonicalTrendingLocalSongs: StateFlow<List<Song>> =
        _canonicalTrendingLocalSongs.asStateFlow()
    /** Public cumulative community counts, never local/personal play history. */
    private val _canonicalExplorePublicPopularity =
        MutableStateFlow<List<ListenBrainzPopularityEvidence>>(emptyList())
    internal val canonicalExplorePublicPopularity: StateFlow<List<ListenBrainzPopularityEvidence>> =
        _canonicalExplorePublicPopularity.asStateFlow()
    private val listenBrainzPopularityClient = ListenBrainzPopularityClient()
    private val _canonicalExplorePersonalRecommendations =
        MutableStateFlow(CanonicalPersonalRecommendationState())
    internal val canonicalExplorePersonalRecommendations: StateFlow<CanonicalPersonalRecommendationState> =
        _canonicalExplorePersonalRecommendations.asStateFlow()
    private val personalRecommendationClient = ListenBrainzRecommendationClient()
    private val personalRecommendationEpoch = DiscoveryRequestEpoch()
    private var personalRecommendationJob: Job? = null
    private var personalRecommendationAccountDigest: String? = null
    @Volatile private var personalRecommendationRetryAtMs = 0L
    private val _canonicalExploreSitewide =
        MutableStateFlow(CanonicalSitewideDiscoveryState())
    internal val canonicalExploreSitewide: StateFlow<CanonicalSitewideDiscoveryState> =
        _canonicalExploreSitewide.asStateFlow()
    private val sitewideEpoch = DiscoveryRequestEpoch()
    private var sitewideJob: Job? = null
    @Volatile private var sitewideRetryAfterMs: Long = 0L
    private val explorePublicPopularityEpoch = DiscoveryRequestEpoch()
    private var explorePublicPopularityJob: Job? = null
    @Volatile private var explorePublicPopularityFreshBatch: Pair<List<String>, Long>? = null
    @Volatile private var explorePublicPopularityLoadingBatch: List<String>? = null
    private val _canonicalArtistPublicTop = MutableStateFlow(CanonicalArtistPublicTopState())
    internal val canonicalArtistPublicTop: StateFlow<CanonicalArtistPublicTopState> =
        _canonicalArtistPublicTop.asStateFlow()
    private val artistPublicTopEpoch = DiscoveryRequestEpoch()
    private var artistPublicTopJob: Job? = null
    private val artistTopMetadataProvider by lazy { MusicBrainzMetadataEnrichmentProvider() }
    private val artistTopMetadataCache = ConcurrentHashMap<String, RecordingMetadataEnrichment>()


    // Public metadata discovery must work on a fresh installation with an
    // empty Room catalog. This feed never manufactures a playable MediaAsset.
    private val _canonicalWorldwideReleases =
        MutableStateFlow<List<CanonicalCatalogSearchRow>>(emptyList())
    val canonicalWorldwideReleases: StateFlow<List<CanonicalCatalogSearchRow>> =
        _canonicalWorldwideReleases.asStateFlow()
    private val _canonicalWorldwideLoading = MutableStateFlow(false)
    val canonicalWorldwideLoading: StateFlow<Boolean> =
        _canonicalWorldwideLoading.asStateFlow()
    private var worldwideReleaseJob: Job? = null
    private val worldwideReleaseEpoch = DiscoveryRequestEpoch()
    private var worldwideReleaseFetchedAtMs: Long = 0L
    private var worldwideReleaseRetryAtMs: Long = 0L

    private val _canonicalGenreResults =
        MutableStateFlow<UiState<List<CanonicalCatalogSearchRow>>>(UiState.Loading)
    val canonicalGenreResults: StateFlow<UiState<List<CanonicalCatalogSearchRow>>> =
        _canonicalGenreResults.asStateFlow()
    private var canonicalGenreJob: Job? = null
    private val canonicalGenreEpoch = DiscoveryRequestEpoch()

    private val canonicalDiscoveryCatalogEpoch = DiscoveryRequestEpoch()
    private var canonicalDiscoveryCatalogJob: Job? = null

    private val _selectedMoodGenre = MutableStateFlow<MoodGenre?>(null)
    val selectedMoodGenre: StateFlow<MoodGenre?> = _selectedMoodGenre.asStateFlow()

    private val _moodGenreShelves = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val moodGenreShelves: StateFlow<UiState<List<HomeShelf>>> = _moodGenreShelves.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    /** The mixed YouTube Music result page is the fast, useful default. */
    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()

    /** Increments once per first-page request so the UI can reset its list. */
    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    /** True while a committed search is in flight — gates suggestion/media callbacks. */
    private var searchSubmitted = false

    /**
     * What the search page offers while a query is being typed, led by the
     * query itself.
     *
     * Non-empty *is* the signal that the field is mid-edit, so the screen
     * needs no second flag: these rows are shown in place of the results
     * whenever there are any, and cleared the moment a search is actually run
     * — see [submitSearch], [searchFor].
     *
     * Element 0 is always the raw text as typed. It's put there by the
     * keystroke itself rather than taken from the response, so the row the
     * thumb is already heading for is correct before the network answers, and
     * stays correct if it never does — YouTube's list never contains the
     * half-typed text, only completions of it.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /**
     * Live media results shown alongside typeahead suggestions. Populated by a
     * lightweight search that runs in parallel with text completions; the UI
     * renders these as playable track cards and browse items below the text
     * suggestion rows. Cleared when the user commits to a search or empties
     * the field.
     */
    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeaheadResults: StateFlow<List<SearchResult>> = _typeaheadResults.asStateFlow()

    /** Where the Search tab looks — see [setSearchSource]. */
    private var searchSourceExplicitlySelected = false
    private var suggestPipelineJob: Job? = null
    private var typeaheadPipelineJob: Job? = null
    private val _searchSource = MutableStateFlow(defaultDiscoverySearchSource(
        AppSettings.canonicalDiscoveryEnabled.value,
        SourceRegistry.configs.value,
    ))
    val searchSource: StateFlow<SearchSource> = _searchSource.asStateFlow()

    /**
     * The Addons source's answer for the submitted query: every enabled addon
     * searched at once and their rows interleaved. Null until something has
     * been submitted. These songs carry source track keys, so a tap plays the
     * addon's own stream with no YouTube lookup in between.
     */
    private val _addonResults = MutableStateFlow<UiState<List<Song>>?>(null)
    val addonResults: StateFlow<UiState<List<Song>>?> = _addonResults.asStateFlow()
    private var addonSearchJob: Job? = null

    /** Search of synced remote-provider assets, keyed by canonical RecordingId. */
    private val _serverResults = MutableStateFlow<UiState<List<CanonicalServerSearchRow>>?>(null)
    val serverResults: StateFlow<UiState<List<CanonicalServerSearchRow>>?> = _serverResults.asStateFlow()
    private var serverSearchJob: Job? = null

    /** Public MusicBrainz identities are not Song/videoId and cannot be queued. */
    private val _canonicalResults = MutableStateFlow<UiState<List<CanonicalCatalogSearchRow>>?>(null)
    val canonicalResults: StateFlow<UiState<List<CanonicalCatalogSearchRow>>?> = _canonicalResults.asStateFlow()
    private var canonicalSearchJob: Job? = null
    private var canonicalTypeaheadJob: Job? = null
    private var canonicalSearchPagingJob: Job? = null
    private var canonicalSearchPageCursors: CanonicalSearchPageCursors? = null
    private val _canonicalSearchHasMore = MutableStateFlow(false)
    val canonicalSearchHasMore: StateFlow<Boolean> = _canonicalSearchHasMore.asStateFlow()
    private val _canonicalSearchLoadingMore = MutableStateFlow(false)
    val canonicalSearchLoadingMore: StateFlow<Boolean> = _canonicalSearchLoadingMore.asStateFlow()

    private fun resetCanonicalSearchPagination() {
        canonicalSearchPagingJob?.cancel()
        canonicalSearchPageCursors = null
        _canonicalSearchHasMore.value = false
        _canonicalSearchLoadingMore.value = false
    }

    /**
     * Device-possession decoration, separate from Media3's playable-asset gate.
     * Use only a strong MusicBrainz Recording -> local-mediastore join, then
     * probe real content URIs on IO. A Server asset or title match is not a download.
     */
    private suspend fun withVerifiedLocalBadges(
        rows: List<CanonicalCatalogSearchRow>,
    ): List<CanonicalCatalogSearchRow> {
        val mbids = rows.asSequence()
            .filter { it.kind == CanonicalSearchKind.RECORDING }
            .map { it.musicBrainzId }
            .distinct().toList()
        if (mbids.isEmpty()) return rows
        return withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val claims = try {
                CanonicalCatalogDatabase.get(app).catalogDao()
                    .musicBrainzRecordingLocalAssetClaims(mbids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            val readability = mutableMapOf<String, Boolean>()
            val local = canonicalVerifiedLocalBadgeMbids(claims) { rawUri ->
                readability.getOrPut(rawUri) {
                    try {
                        app.contentResolver.openFileDescriptor(
                            android.net.Uri.parse(rawUri), "r",
                        )?.use { it.statSize != 0L } == true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            rows.map { row ->
                if (row.kind == CanonicalSearchKind.RECORDING) {
                    row.copy(hasVerifiedLocalAsset = row.musicBrainzId in local)
                } else row
            }
        }
    }

    private val musicBrainzSearch by lazy { MusicBrainzCatalogSearch() }
    private val musicBrainzGraph by lazy { MusicBrainzCatalogGraph() }
    private val coverArtArchive by lazy {
        CoverArtArchive(store = RoomMusicBrainzCoverArtStore(
            CanonicalCatalogDatabase.get(getApplication<Application>()).catalogDao(),
        ))
    }
    private var canonicalSearchCoverJob: Job? = null
    private var canonicalHomeCoverJob: Job? = null

    /**
     * Fill only genuine CAA artwork on an IO worker. A 404 or temporary error
     * remains an empty cover; it must not borrow a similarly named album.
     */
    /**
     * A Group can have no CAA group front while a release explicitly linked
     * to it has the cover. No fuzzy title/artist search, even as fallback.
     * An actual edition must always retain its own artwork authority.
     */
    private suspend fun canonicalArtworkForRow(
        row: CanonicalCatalogSearchRow,
        size: CoverArtSize = CoverArtSize.THUMBNAIL,
        prefetchedOfficialEditions: List<MusicBrainzReleaseHit>? = null,
    ): String? {
        // MusicBrainz "Other" is not positive evidence for a release sleeve.
        // An unrelated release edition might be a video whose art is a still.
        if (row.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(row) == null
        ) return null
        if (row.kind == CanonicalSearchKind.RELEASE_GROUP) {
            // Fast path on revisit: group art must come from a vetted
            // Official edition linked by MBID, not matching group title.
            val cachedSmallSleeve = coverArtArchive.cachedVerifiedGroupCover(
                row.musicBrainzId, size,
            )
            // A saved 250px sleeve stays visible in the UI while better CAA
            // artwork is sought. It must not permanently block an eventual
            // 500px Front upgrade merely because the old URI was non-null.
            if (cachedSmallSleeve != null && !canonicalNeedsCoverRefresh(cachedSmallSleeve)) {
                return cachedSmallSleeve
            }
            val index = MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                getApplication<Application>(),
            ))
            val saved = try { index.cachedGroupReleases(row.musicBrainzId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            var vetted = canonicalArtworkEditionsForGroup(
                prefetchedOfficialEditions.orEmpty(), row.title,
            ).ifEmpty { canonicalArtworkEditionsForGroup(saved, row.title) }
            if (vetted.isEmpty()) {
                val fetched = musicBrainzGraph.browseGroupReleases(row.musicBrainzId, limit = 40)
                if (fetched is MusicBrainzCatalogGraphResult.Success) {
                    try { index.persistGroupEditions(row.musicBrainzId, fetched.value.items) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Fresh response remains usable. */ }
                    vetted = canonicalArtworkEditionsForGroup(fetched.value.items, row.title)
                }
            }
            for (edition in vetted) {
                // MusicBrainz's front=true is only a presence hint. It does
                // NOT prove CAA's image isn't tagged Poster/Track, and the
                // direct /front-500 URL bypasses strict JSON validation.
                // Resolve the typed Front once, then reuse the Room cache.
                // No group-front API shortcut: it may point to a video or an
                // alternate artwork edition even under the correct group MBID.
                val hero = coverArtArchive.findCover(
                    edition.musicBrainzId, null, CoverArtSize.HERO,
                ) ?: continue
                val thumb = coverArtArchive.findCover(
                    edition.musicBrainzId, null, CoverArtSize.THUMBNAIL,
                ) ?: hero
                try {
                    // Preserve the actual Official Release metadata too: cold
                    // sessions can reuse both its Front and editorial title.
                    // Only exact group-membership releases are supplied here.
                    index.persistGroupEditions(row.musicBrainzId, listOf(edition))
                    RoomMusicBrainzCoverArtStore(
                        CanonicalCatalogDatabase.get(getApplication<Application>()).catalogDao(),
                    ).write(MusicBrainzCoverArtCacheEntry(
                        "verified-artist-group", row.musicBrainzId, hero,
                        System.currentTimeMillis(), thumb,
                    ))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Valid art still renders if cache is unavailable. */ }
                return if (size == CoverArtSize.THUMBNAIL) thumb else hero
            }
            return cachedSmallSleeve
        }
        val target = canonicalCoverLookupTarget(row)
        if (row.kind == CanonicalSearchKind.RECORDING) {
            // The MusicBrainz search response already proves an Official
            // Recording -> audio Release -> ReleaseGroup relationship.
            // Reuse its verified Front-v5 before opening the Room database.
            row.coverGroupMbid?.let { officialGroup ->
                coverArtArchive.cachedVerifiedGroupCover(officialGroup, size)?.let {
                    return it
                }
            }
            // Only offline recordings lacking live group membership may use
            // a Room Track -> Official Release -> ReleaseGroup edge. If the
            // search response already proves an exact group but its Front is
            // absent, showing a different (often compilation) sleeve is worse
            // than waiting for the explicitly linked edition below.
            if (canonicalCanUseCachedRecordingGroupEdge(row)) {
                val ownedGroupMbid = try {
                    CanonicalCatalogDatabase.get(getApplication<Application>())
                        .catalogDao().verifiedRecordingCoverGroupMbid(row.musicBrainzId)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { null }
                if (ownedGroupMbid != null) {
                    coverArtArchive.cachedVerifiedGroupCover(ownedGroupMbid, size)?.let {
                        return it
                    }
                }
            }
            // Otherwise only an independently verified Official Release ID
            // may supply this Recording's cover, not an arbitrary compilation
            // or a similarly named video/performance recording.
            val verifiedEdition = row.coverReleaseMbid ?: return null
            return coverArtArchive.findCover(verifiedEdition, null, size)
        }
        if (target == null) return null
        return coverArtArchive.findCover(target.first, target.second, size)
    }

    /** Read only linked, Official edition titles; never match by artist/title. */
    private suspend fun canonicalSavedGroupDisplayTitle(row: CanonicalCatalogSearchRow): String {
        // Only clear composite group labels qualify for an edition-backed
        // display rename. Most shelves contain ordinary titles; avoid one
        // Room edition traversal per ordinary visible card.
        if (row.kind != CanonicalSearchKind.RELEASE_GROUP ||
            !row.title.contains(" & ")
        ) return row.title
        val editions = try {
            MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                getApplication<Application>(),
            )).cachedGroupReleases(row.musicBrainzId)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
        return canonicalOfficialGroupDisplayTitle(row.title, editions)
    }

    /** Album covers discovered after the initial search must propagate to
     * still-visible recordings through MusicBrainz MBIDs, never title guesses.
     * This refresh reads Room only; it does not enqueue more CAA HTTP calls. */
    private var canonicalSearchAlbumFanoutJob: Job? = null

    private fun refreshCanonicalSearchRecordingAlbumArtwork() {
        val query = _query.value.trim()
        if (_searchSource.value != SearchSource.CANONICAL || query.isEmpty()) return
        val pending = (_canonicalResults.value as? UiState.Success)?.data.orEmpty()
            .filter {
                it.kind == CanonicalSearchKind.RECORDING &&
                    (it.artworkUri.isNullOrBlank() ||
                        it.artworkProvenance ==
                            com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE)
            }.take(48)
        if (pending.isEmpty()) return
        canonicalSearchAlbumFanoutJob?.cancel()
        canonicalSearchAlbumFanoutJob = viewModelScope.launch(Dispatchers.IO) {
            val proven = try {
                canonicalCachedRecordingArtForVisibleRows(
                    rows = pending,
                    databaseLinks = { ids ->
                        CanonicalCatalogDatabase.get(getApplication<Application>())
                            .catalogDao().verifiedRecordingCoverGroupMbids(ids)
                    },
                    cachedCover = { group ->
                        coverArtArchive.cachedVerifiedGroupCover(group, CoverArtSize.THUMBNAIL)
                    },
                    cachedCovers = { groups ->
                        coverArtArchive.cachedVerifiedGroupCovers(
                            groups, CoverArtSize.THUMBNAIL,
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { emptyMap() }
            if (proven.isEmpty() ||
                _searchSource.value != SearchSource.CANONICAL ||
                _query.value.trim() != query
            ) return@launch
            _canonicalResults.update { old ->
                if (old !is UiState.Success) old
                else UiState.Success(canonicalRowsWithVerifiedRecordingArt(old.data, proven))
            }
        }
    }

    private fun enrichCanonicalSearchCovers(query: String) {
        canonicalSearchCoverJob?.cancel()
        val visible = (_canonicalResults.value as? UiState.Success)?.data.orEmpty()
        val prioritized = visible.sortedByDescending { row ->
            row.subtitle?.contains(query, ignoreCase = true) == true
        }
        // Balance visible release sleeves and song covers instead of allowing
        // twenty matching recordings to starve all release/group artwork.
        val candidates = canonicalSearchCoverPriorityQueue(prioritized)
        // Independent Apple artwork is a visual fallback for the best exact
        // artist-name hit, not an identity link between Apple and MusicBrainz.
        val portrait = canonicalArtistArtworkCandidate(visible, query)
        // A release already inspected by this user has verified Track ->
        // Recording associations in Room. Rehydrate those visible songs from
        // the same group sleeve without replaying up to 24 archive lookups.
        val knownTracks = visible.filter {
            it.kind == CanonicalSearchKind.RECORDING &&
                (canonicalNeedsCoverRefresh(it.artworkUri) ||
                    it.artworkProvenance ==
                        com.music.bitchord.ui.screens.CanonicalArtworkProvenance.INHERITED_RELEASE)
        }.take(48)
        val warmGroupIds = canonicalWarmGroupArtworkCandidateMbids(visible, limit = 48)
        if (candidates.isEmpty() && portrait == null && knownTracks.isEmpty() &&
            warmGroupIds.isEmpty()
        ) return
        canonicalSearchCoverJob = viewModelScope.launch(Dispatchers.IO) {
            // Warm group art is already verified Front-v5 in Room: hydrate
            // albums AND their directly related songs in a single query/state
            // update before allocating CAA network permits.
            val warmGroups = try {
                coverArtArchive.cachedVerifiedGroupCovers(warmGroupIds, CoverArtSize.THUMBNAIL)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { emptyMap() }
            if (warmGroups.isNotEmpty() &&
                _searchSource.value == SearchSource.CANONICAL &&
                _query.value.trim() == query
            ) _canonicalResults.update { old ->
                if (old !is UiState.Success) old
                else UiState.Success(canonicalRowsWithVerifiedGroupCoverBatch(
                    old.data, warmGroups,
                ))
            }
            // First resolve the album artwork already in Room. One SQL
            // snapshot for the visible recordings replaces up to 24 separate
            // Recording→Track→Release→Group graph traversals.
            // Search's recording CAA requests await this result so an already
            // loaded album cannot trigger a redundant remote lookup.
            val reusedRecordingCovers = async {
                // Album groups already hydrated their directly related
                // recording covers in the previous single Room query.
                val unresolved = knownTracks.filter {
                    it.coverGroupMbid?.let(warmGroups::containsKey) != true
                }
                if (unresolved.isEmpty()) return@async emptyMap<String, String>()
                val proven = try {
                    canonicalCachedRecordingArtForVisibleRows(
                        rows = unresolved,
                        databaseLinks = { mbids ->
                            // Direct MusicBrainz -> verified Front-v5 cache
                            // often resolves all songs. Don't initialize Room
                            // unless unresolved recordings actually need it.
                            CanonicalCatalogDatabase.get(getApplication<Application>())
                                .catalogDao().verifiedRecordingCoverGroupMbids(mbids)
                        },
                        cachedCover = { group ->
                            coverArtArchive.cachedVerifiedGroupCover(
                                group, CoverArtSize.THUMBNAIL,
                            )
                        },
                        cachedCovers = { groups ->
                            coverArtArchive.cachedVerifiedGroupCovers(
                                groups, CoverArtSize.THUMBNAIL,
                            )
                        },
                    )
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) {
                    // A damaged/missing Room cache must not block search.
                    emptyMap<String, String>()
                }
                if (proven.isNotEmpty() &&
                    _searchSource.value == SearchSource.CANONICAL &&
                    _query.value.trim() == query
                ) _canonicalResults.update { old ->
                    if (old !is UiState.Success) old else UiState.Success(
                        canonicalRowsWithVerifiedRecordingArt(old.data, proven),
                    )
                }
                proven
            }
            // Artist portrait is independent decoration: don't block the
            // entire album/recording cover queue on the Apple art endpoint.
            if (portrait != null) launch {
                val art = try {
                    AppleArtistArtRepository.artFor(portrait.title)?.heroUrl
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (art != null && _searchSource.value == SearchSource.CANONICAL &&
                    _query.value.trim() == query
                ) _canonicalResults.update { old ->
                    if (old !is UiState.Success) old else UiState.Success(old.data.map {
                        if (it.kind == CanonicalSearchKind.ARTIST &&
                            it.musicBrainzId == portrait.musicBrainzId &&
                            it.artworkUri.isNullOrBlank()) it.copy(artworkUri = art) else it
                    })
                }
            }
            // Visible results should not wait behind one stalled CAA request.
            // CAA enrichment is bounded; MusicBrainz lookups retain their own
            // process-wide rate limiter. Different rows must update atomically.
            val permits = Semaphore(3)
            candidates.forEach { row ->
                launch {
                    // Do not re-read the same verified Front-v5 one by one
                    // after publishing the single warm batch. Composite group
                    // titles may still need edition-backed display naming.
                    if (row.kind == CanonicalSearchKind.RELEASE_GROUP &&
                        row.musicBrainzId in warmGroups &&
                        !canonicalNeedsCoverRefresh(warmGroups[row.musicBrainzId]) &&
                        !row.title.contains(" & ")
                    ) return@launch
                    if (row.kind == CanonicalSearchKind.RECORDING &&
                        row.coverGroupMbid?.let(warmGroups::containsKey) == true
                    ) return@launch
                    // Let release covers stream immediately. Recording covers
                    // first reuse their actual Official Album sleeve from
                    // Room; waiting here (outside the permit) preserves all
                    // three CAA slots for genuinely missing artwork.
                    if (row.kind == CanonicalSearchKind.RECORDING &&
                        row.musicBrainzId in reusedRecordingCovers.await()
                    ) return@launch
                    permits.withPermit {
                        if (_searchSource.value != SearchSource.CANONICAL ||
                            _query.value.trim() != query) return@withPermit
                        val artwork = try {
                            canonicalArtworkForRow(row)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                        val officialTitle = canonicalSavedGroupDisplayTitle(row)
                        if (artwork == null && officialTitle == row.title) return@withPermit
                        if (_searchSource.value != SearchSource.CANONICAL ||
                            _query.value.trim() != query) return@withPermit
                        _canonicalResults.update { old ->
                            if (old !is UiState.Success) old else {
                                // The verified album sleeve can update multiple
                                // same-group songs immediately, without
                                // waiting for another search or Room traversal.
                                val withAlbumArt = if (row.kind ==
                                    CanonicalSearchKind.RELEASE_GROUP && artwork != null
                                ) canonicalRowsWithNewVerifiedGroupArt(
                                    old.data, row.musicBrainzId, artwork,
                                ) else old.data
                                UiState.Success(withAlbumArt.map {
                                    if (it.kind != row.kind || it.musicBrainzId != row.musicBrainzId) it
                                    else {
                                        val resolved = canonicalRecordingWithInheritedReleaseArt(
                                            it, artwork,
                                        )
                                        resolved.copy(
                                            title = officialTitle,
                                            artworkUri = if (
                                                it.kind != CanonicalSearchKind.RECORDING &&
                                                artwork != null &&
                                                canonicalNeedsCoverRefresh(it.artworkUri)
                                            ) artwork else resolved.artworkUri,
                                        )
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enrichCanonicalHomeCovers(generation: Long) {
        canonicalHomeCoverJob?.cancel()
        val current = (_canonicalDiscoveryCatalog.value as? UiState.Success)?.data.orEmpty()
        val prioritized = current.sortedWith(
            compareByDescending<CanonicalCatalogSearchRow> { it.releaseDate.orEmpty() }
                .thenBy { it.musicBrainzId },
        )
        val candidates = canonicalCoverLookupCandidates(prioritized, limit = 24)
        val warmGroupIds = canonicalWarmGroupArtworkCandidateMbids(
            prioritized, limit = 48,
        )
        if (candidates.isEmpty() && warmGroupIds.isEmpty()) return
        canonicalHomeCoverJob = viewModelScope.launch(Dispatchers.IO) {
            // Home is revisited much more often than a new album is fetched.
            // One Room batch reuses already-approved CAA sleeves without
            // repeated per-row DB reads or network metadata lookups.
            val warmGroups = try {
                coverArtArchive.cachedVerifiedGroupCovers(
                    warmGroupIds, CoverArtSize.THUMBNAIL,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { emptyMap() }
            if (warmGroups.isNotEmpty() &&
                canonicalDiscoveryCatalogEpoch.isCurrent(generation) &&
                isCanonicalDiscoverySelected()
            ) _canonicalDiscoveryCatalog.update { old ->
                if (old !is UiState.Success) old else UiState.Success(
                    canonicalRowsWithVerifiedGroupCoverBatch(old.data, warmGroups),
                )
            }
            val permits = Semaphore(3)
            candidates.forEach { row ->
                launch {
                    if (row.kind == CanonicalSearchKind.RELEASE_GROUP &&
                        row.musicBrainzId in warmGroups &&
                        !canonicalNeedsCoverRefresh(warmGroups[row.musicBrainzId]) &&
                        !row.title.contains(" & ")
                    ) return@launch
                    permits.withPermit {
                        if (!canonicalDiscoveryCatalogEpoch.isCurrent(generation) ||
                            !isCanonicalDiscoverySelected()) return@withPermit
                        val artwork = try {
                            canonicalArtworkForRow(row)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                        val officialTitle = canonicalSavedGroupDisplayTitle(row)
                        if (artwork == null && officialTitle == row.title) return@withPermit
                        if (!canonicalDiscoveryCatalogEpoch.isCurrent(generation) ||
                            !isCanonicalDiscoverySelected()) return@withPermit
                        _canonicalDiscoveryCatalog.update { old ->
                            if (old !is UiState.Success) old else UiState.Success(
                                old.data.map {
                                    if (it.kind != row.kind || it.musicBrainzId != row.musicBrainzId) it
                                    else it.copy(
                                        title = officialTitle,
                                        artworkUri = if (artwork != null &&
                                            canonicalNeedsCoverRefresh(it.artworkUri))
                                            artwork else it.artworkUri,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private val _canonicalDetail = MutableStateFlow<UiState<CanonicalCatalogDetailPage>?>(null)
    val canonicalDetail: StateFlow<UiState<CanonicalCatalogDetailPage>?> = _canonicalDetail.asStateFlow()
    private var canonicalBrowseJob: Job? = null
    private var searchPagingJob: Job? = null
    private val _canonicalLoadingMore = MutableStateFlow(false)
    val canonicalLoadingMore: StateFlow<Boolean> = _canonicalLoadingMore.asStateFlow()
    private var canonicalPagingJob: Job? = null

    /**
     * MusicBrainz browse pages can be shorter than the requested limit due to
     * its 500-track response cap. Never assume offset += 25.
     */
    fun loadMoreCanonicalDetail() {
        val entry = canonicalPath.lastOrNull() ?: return
        val current = (_canonicalDetail.value as? UiState.Success)?.data ?: return
        val offset = current.nextOffset ?: return
        if (_searchSource.value != SearchSource.CANONICAL || _canonicalLoadingMore.value ||
            (entry.kind != CanonicalSearchKind.ARTIST && entry.kind != CanonicalSearchKind.RELEASE_GROUP)) return
        _canonicalLoadingMore.value = true
        canonicalPagingJob?.cancel()
        canonicalPagingJob = viewModelScope.launch {
            try {
                val page = when (entry.kind) {
                    CanonicalSearchKind.ARTIST -> when (
                        val result = musicBrainzGraph.browseReleaseGroupsForArtist(entry.musicBrainzId, offset = offset)
                    ) {
                        is MusicBrainzCatalogGraphResult.Success -> {
                            try {
                                withContext(Dispatchers.IO) {
                                    MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                                        getApplication<Application>(),
                                    )).persistArtistGroups(entry.musicBrainzId, result.value.items)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) { /* Live results remain usable. */ }
                            result.value.items.map { hit ->
                                CanonicalCatalogSearchRow(
                                    hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP,
                                    hit.title, hit.artistDisplay ?: hit.primaryType,
                                    releaseDate = hit.firstReleaseDate,
                                    releaseKind = hit.primaryType,
                                    secondaryTypes = hit.secondaryTypes,
                                )
                            } to result.value.nextOffset
                        }
                        is MusicBrainzCatalogGraphResult.Failure -> null
                    }
                    CanonicalSearchKind.RELEASE_GROUP -> when (
                        val result = musicBrainzGraph.browseGroupReleases(entry.musicBrainzId, offset = offset)
                    ) {
                        is MusicBrainzCatalogGraphResult.Success -> {
                            try {
                                withContext(Dispatchers.IO) {
                                    MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                                        getApplication<Application>(),
                                    )).persistGroupEditions(entry.musicBrainzId, result.value.items)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) { /* No false offline claim; live list still usable. */ }
                            result.value.items.filter(::isCuratedMusicBrainzRelease).map { hit ->
                                CanonicalCatalogSearchRow(hit.musicBrainzId, CanonicalSearchKind.RELEASE,
                                    hit.title, listOfNotNull(hit.country, hit.releaseDate).joinToString(" · "),
                                    releaseDate = hit.releaseDate,
                                    coverGroupMbid = hit.releaseGroupMbid,
                                )
                            } to result.value.nextOffset
                        }
                        is MusicBrainzCatalogGraphResult.Failure -> null
                    }
                    else -> null
                }
                val stillCurrent = (_canonicalDetail.value as? UiState.Success)?.data
                if (page != null && _searchSource.value == SearchSource.CANONICAL &&
                    canonicalPath.lastOrNull() == entry && stillCurrent != null &&
                    stillCurrent.nextOffset == offset
                ) {
                    val merged = (stillCurrent.rows + page.first)
                        .distinctBy { it.kind to it.musicBrainzId }
                    _canonicalDetail.value = UiState.Success(stillCurrent.copy(
                        rows = merged,
                        nextOffset = page.second,
                        summary = "${merged.size} of ${stillCurrent.totalCount ?: merged.size} catalog entries",
                    ))
                    // Manual next-page clicks must refresh the same native
                    // shelves as auto-pagination. Search-only rows aren't
                    // enough for the Android artist DetailScreen.
                    val nativeId = canonicalNativeBrowseId(entry)
                    _detailStack.value = _detailStack.value.map { detail ->
                        if (detail.browseId != nativeId) detail else detail.copy(
                            sections = canonicalNativeSections(
                                parentKind = entry.kind, rows = merged,
                                heading = getApplication<Application>().getString(
                                    if (entry.kind == CanonicalSearchKind.ARTIST)
                                        R.string.shelf_new_albums_singles else R.string.albums),
                                albumsHeading = getApplication<Application>().getString(R.string.albums),
                                singlesHeading = getApplication<Application>().getString(
                                    R.string.canonical_singles_eps),
                            ),
                        )
                    }
                    if (entry.kind == CanonicalSearchKind.ARTIST)
                        enrichCanonicalNativeDetailCovers(entry)
                }
            } finally {
                _canonicalLoadingMore.value = false
            }
        }
    }
    private val canonicalPath = mutableListOf<CanonicalCatalogSearchRow>()
    private var canonicalDetailCoverJob: Job? = null

    /**
     * Fill visible native artist shelves progressively. Group MBIDs are the
     * identity boundary; fallback artwork may be from a Release known to
     * belong to that exact group, never from fuzzy name matching.
     */
    private var activeDetailCoverArtistMbid: String? = null

    private fun enrichCanonicalNativeDetailCovers(artist: CanonicalCatalogSearchRow) {
        // A new page of 75 groups must not cancel an ongoing cover request
        // queue; older code restarted the first 12 on each pagination.
        if (activeDetailCoverArtistMbid == artist.musicBrainzId &&
            canonicalDetailCoverJob?.isActive == true
        ) return
        canonicalDetailCoverJob?.cancel()
        activeDetailCoverArtistMbid = artist.musicBrainzId
        canonicalDetailCoverJob = viewModelScope.launch(Dispatchers.IO) {
            // One artist-level API page supplies official ReleaseGroup edges
            // for most small/medium discographies. Falls back per-group only
            // when no matching verified edition was included in that page.
            val offlineOnly = (_canonicalDetail.value as? UiState.Success)
                ?.data?.rows?.firstOrNull()?.fromOfflineCache == true
            var firstEditionPage: MusicBrainzCatalogPage<MusicBrainzReleaseHit>? = null
            // Additional edition pages now stream concurrently with CAA
            // lookups. The map must support safe independent IO workers.
            val artistEditions = java.util.concurrent.ConcurrentHashMap<
                String, List<MusicBrainzReleaseHit>>()
            // Navidrome/Auxio-inspired cache-first artist shelves: artwork already
            // vetted in Room must never wait for an external MusicBrainz
            // official-edition browse (which has a shared rate limit). A single
            // SQL batch hydrates the still-blank cards while the online index
            // is obtained independently. This does not invent group identity.
            launch {
                // A filled 500px thumbnail can still be an earlier, wrong
                // edition sleeve. Revalidate all visible Official audio groups,
                // not only empty/250px cards. Bound the Room IN query and let
                // the normal progressive worker cover later paginated groups.
                val visibleRows = (_canonicalDetail.value as? UiState.Success)
                    ?.data?.rows.orEmpty()
                val candidateIds = canonicalWarmGroupArtworkCandidateMbids(
                    visibleRows, limit = 400,
                )
                if (candidateIds.isNotEmpty()) {
                    val verified = coverArtArchive.cachedVerifiedGroupCovers(
                        candidateIds, CoverArtSize.THUMBNAIL,
                    )
                    if (verified.isNotEmpty() &&
                        _searchSource.value == SearchSource.CANONICAL &&
                        canonicalPath.lastOrNull() == artist
                    ) {
                        val latestRows = (_canonicalDetail.value as? UiState.Success)
                            ?.data?.rows.orEmpty()
                        val updates = canonicalArtistVerifiedGroupUpdates(
                            latestRows, verified,
                        )
                        if (updates.isNotEmpty()) {
                            val applicable = updates.mapValues { it.value.first }
                            _canonicalDetail.update { old ->
                                if (old !is UiState.Success) old else UiState.Success(
                                    old.data.copy(rows = canonicalRowsWithVerifiedGroupCoverBatch(
                                        old.data.rows, applicable,
                                    )),
                                )
                            }
                            val artistId = canonicalNativeBrowseId(artist)
                            _detailStack.update { pages ->
                                pages.map { page ->
                                    if (page.browseId != artistId) page else page.copy(
                                        sections = canonicalArtistShelvesWithCoverUpdates(
                                            page.sections, updates,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!offlineOnly) try {
                when (val batch = musicBrainzGraph.browseOfficialReleasesForArtist(
                    artist.musicBrainzId, limit = 100,
                )) {
                    is MusicBrainzCatalogGraphResult.Success -> {
                        firstEditionPage = batch.value
                        artistEditions.putAll(batch.value.items.filter {
                            isCuratedMusicBrainzRelease(it) && it.releaseGroupMbid != null
                        }.groupBy { requireNotNull(it.releaseGroupMbid) })
                    }
                    is MusicBrainzCatalogGraphResult.Failure -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Stored editions remain available. */ }
            // Editorial labels come from Official edition evidence, not CAA
            // availability: a correct name must survive even with no artwork.
            // Persist only the groups with a provable composite -> edition
            // correction, keeping Room's raw ReleaseGroup title unchanged.
            val titleCorrections = (_canonicalDetail.value as? UiState.Success)
                ?.data?.rows.orEmpty().mapNotNull { row ->
                    if (row.kind != CanonicalSearchKind.RELEASE_GROUP) return@mapNotNull null
                    val editions = artistEditions[row.musicBrainzId].orEmpty()
                    val label = canonicalOfficialGroupDisplayTitle(row.title, editions)
                    if (label == row.title) null else Triple(row.musicBrainzId, label, editions)
                }
            if (titleCorrections.isNotEmpty() &&
                _searchSource.value == SearchSource.CANONICAL &&
                canonicalPath.lastOrNull() == artist
            ) {
                try {
                    val index = MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                        getApplication<Application>(),
                    ))
                    titleCorrections.forEach { (mbid, _, editions) ->
                        index.persistGroupEditions(mbid, editions)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { /* On-screen evidence still applies. */ }
                val corrections = titleCorrections.associate { it.first to it.second }
                _canonicalDetail.update { old ->
                    if (old !is UiState.Success) old else UiState.Success(old.data.copy(
                        rows = old.data.rows.map { row ->
                            corrections[row.musicBrainzId]?.let { row.copy(title = it) } ?: row
                        },
                    ))
                }
                val nativeId = canonicalNativeBrowseId(artist)
                _detailStack.update { stack ->
                    val rows = (_canonicalDetail.value as? UiState.Success)?.data?.rows.orEmpty()
                    stack.map { page ->
                        if (page.browseId != nativeId) page else page.copy(
                            sections = canonicalNativeSections(
                                parentKind = CanonicalSearchKind.ARTIST, rows = rows,
                                heading = getApplication<Application>().getString(
                                    R.string.shelf_new_albums_singles,
                                ),
                                albumsHeading = getApplication<Application>().getString(
                                    R.string.albums,
                                ),
                                singlesHeading = getApplication<Application>().getString(
                                    R.string.canonical_singles_eps,
                                ),
                            ),
                        )
                    }
                }
            }
            val attempted = mutableSetOf<String>()
            var initialCoverWave = true
            var expandedArtistEditionPages = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (_searchSource.value != SearchSource.CANONICAL ||
                    canonicalPath.lastOrNull() != artist) break
                // Front-load six covers. For long artist discographies, then
                // collect another bounded set of 100-edition artist pages,
                // instead of N rate-limited group browse requests. Resolved
                // MBIDs remain linked; a failed page never erases earlier art.
                val firstPage = firstEditionPage
                val expandNow = !expandedArtistEditionPages &&
                    attempted.size >= 6 && firstPage?.nextOffset != null && !offlineOnly
                if (expandNow) expandedArtistEditionPages = true
                val lateEditionGroups = java.util.concurrent.ConcurrentHashMap
                    .newKeySet<String>()
                val batch = (_canonicalDetail.value as? UiState.Success)?.data?.rows
                    .orEmpty().filter {
                        it.kind == CanonicalSearchKind.RELEASE_GROUP &&
                            canonicalEditorialReleaseType(it) != null &&
                            canonicalNeedsCoverRefresh(it.artworkUri) &&
                            it.musicBrainzId !in attempted
                    }.let { pending ->
                        // Paint the first eight without holding them behind
                        // additional artist browse pages. Once they complete,
                        // stream the entire remaining queue with three fixed
                        // workers, not another 6-at-a-time completion barrier.
                        if (initialCoverWave) pending.take(8) else pending
                    }
                if (batch.isEmpty()) break
                initialCoverWave = false
                attempted.addAll(batch.map { it.musicBrainzId })
                // Stream finished covers immediately instead of withholding an
                // entire six-row wave behind the slowest CAA network lookup.
                // At most three calls are active; bursts coalesce before state
                // updates, and navigation checks prevent stale artwork.
                // The edition-page fetch MUST NOT interrupt the next
                // streaming cover wave: on TWICE-scale discographies three
                // sequential rate-limited MusicBrainz pages were visibly
                // freezing artwork after the first eight covers.
                loadCanonicalCoversWhileExpandingEditions(
                    keys = batch,
                    maxConcurrent = 3,
                    expandEditions = {
                        if (expandNow && firstPage != null) {
                            collectCanonicalArtistOfficialEditionPages(
                                firstPage = firstPage,
                                maxAdditionalPages = 3,
                                fetch = { offset ->
                                    musicBrainzGraph.browseOfficialReleasesForArtist(
                                        artist.musicBrainzId, limit = 100, offset = offset,
                                    )
                                },
                                onPage = { editions ->
                                    lateEditionGroups.addAll(
                                        editions.keys - artistEditions.keys,
                                    )
                                    artistEditions.putAll(editions)
                                },
                            )
                        }
                    },
                    fetch = { group ->
                        val cover = try {
                            canonicalArtworkForRow(
                                group, prefetchedOfficialEditions =
                                    artistEditions[group.musicBrainzId],
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                        val releaseEvidence = artistEditions[group.musicBrainzId]
                            ?: try {
                                MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                                    getApplication<Application>(),
                                )).cachedGroupReleases(group.musicBrainzId)
                            } catch (cancelled: CancellationException) { throw cancelled }
                              catch (_: Exception) { emptyList() }
                        cover?.let {
                            group.musicBrainzId to (it to canonicalOfficialGroupDisplayTitle(
                                group.title, releaseEvidence,
                            ))
                        }
                    },
                    publish = paint@{ ready ->
                        if (_searchSource.value != SearchSource.CANONICAL ||
                            canonicalPath.lastOrNull() != artist) return@paint
                        val results = ready.values.toMap()
                        _canonicalDetail.update { old ->
                            if (old !is UiState.Success) old else UiState.Success(
                                old.data.copy(rows = canonicalArtistRowsWithOfficialCoverUpdates(
                                    old.data.rows, results,
                                )),
                            )
                        }
                        val artistId = canonicalNativeBrowseId(artist)
                        _detailStack.update { pages ->
                            if (_searchSource.value != SearchSource.CANONICAL ||
                                canonicalPath.lastOrNull() != artist) pages else {
                                pages.map { page ->
                                    if (page.browseId != artistId) page else page.copy(
                                        // Already ordered by canonicalNativeSections
                                        // at initial paint and paging. Preserve
                                        // every other card instance; full rebuilds
                                        // were O(discography x covers).
                                        sections = canonicalArtistShelvesWithCoverUpdates(
                                            page.sections, results,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                )
                // Only groups newly supported by late, positively-linked
                // Official editions may retry if their earlier cover failed.
                // Existing success is never reloaded, and expansion is bounded.
                if (expandNow) attempted.removeAll(lateEditionGroups)
                if (_searchSource.value != SearchSource.CANONICAL ||
                    canonicalPath.lastOrNull() != artist) break
            }
        }
    }

    /**
     * Canonical identity uses the same native DetailScreen as the rest of
     * BitChord. A public MBID remains a browse key, NEVER a Song.videoId.
     */
    fun openCanonicalNativeDetail(row: CanonicalCatalogSearchRow) {
        val nativeType = canonicalNativePageType(row.kind) ?: return
        val browseId = canonicalNativeBrowseId(row)
        if (_detailStack.value.lastOrNull()?.browseId == browseId) return
        if (_searchSource.value != SearchSource.CANONICAL) {
            switchSearchSource(SearchSource.CANONICAL, rerunCurrentQuery = false)
        }
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = row.title,
            subtitle = when (row.kind) {
                CanonicalSearchKind.ARTIST -> ""
                CanonicalSearchKind.RELEASE_GROUP ->
                    listOfNotNull(row.subtitle, row.releaseKind?.takeUnless {
                        it.equals("Other", ignoreCase = true)
                    }).filter { it.isNotBlank() }.joinToString(" · ")
                else -> row.subtitle.orEmpty()
            },
            thumbnailUrl = row.artworkUri.takeUnless {
                row.kind == CanonicalSearchKind.RELEASE_GROUP &&
                    canonicalEditorialReleaseType(row) == null
            },
            songs = UiState.Loading,
            type = nativeType,
        )
        openCanonicalCatalogEntry(row)
    }

    fun openCanonicalNativeShelfItem(browseId: String): Boolean {
        if (!browseId.startsWith("catalog:")) return false
        val currentRows = (_canonicalDetail.value as? UiState.Success)?.data?.rows.orEmpty()
        val fromCatalog = (_canonicalDiscoveryCatalog.value as? UiState.Success)?.data.orEmpty()
        val row = (currentRows + fromCatalog).firstOrNull {
            canonicalNativeBrowseId(it) == browseId
        } ?: return false
        openCanonicalNativeDetail(row)
        return true
    }

    fun openCanonicalCatalogEntry(row: CanonicalCatalogSearchRow) {
        if (_searchSource.value != SearchSource.CANONICAL ||
            row.kind == CanonicalSearchKind.RECORDING) return
        canonicalPath.add(row)
        showCanonicalCatalogEntry(row)
    }

    fun backCanonicalCatalogEntry() {
        if (_searchSource.value != SearchSource.CANONICAL) return
        canonicalBrowseJob?.cancel()
        if (canonicalPath.isNotEmpty()) canonicalPath.removeAt(canonicalPath.lastIndex)
        if (canonicalPath.isEmpty()) {
            _canonicalDetail.value = null
            _searchScrollReset.value += 1
            refreshCanonicalSearchRecordingAlbumArtwork()
        } else showCanonicalCatalogEntry(canonicalPath.last())
    }

    private fun clearCanonicalCatalogNavigation() {
        canonicalDetailCoverJob?.cancel()
        canonicalBrowseJob?.cancel()
        canonicalPagingJob?.cancel()
        _canonicalLoadingMore.value = false
        canonicalPath.clear()
        _canonicalDetail.value = null
    }

    /**
     * Native release tracks must never be delayed by CAA/Archive redirects.
     * Artwork updates only the still-open canonical page, never an old browse.
     */
    private fun enrichCanonicalNativeReleaseCover(entry: CanonicalCatalogSearchRow) {
        canonicalDetailCoverJob?.cancel()
        if (entry.kind == CanonicalSearchKind.RELEASE_GROUP &&
            canonicalEditorialReleaseType(entry) == null
        ) return
        // The artist shelf already owns a verified 500px bitmap key. Keep
        // that exact URL on the header instead of replacing it with the 1200px
        // variant and making Coil fetch the same sleeve a second time.
        // Detail tracks also inherit this URL in the initial state.
        // A filled 500px album detail can still show an older edition Front.
        // Official group verification runs off-main; a Release's full-sized
        // exact-edition image can keep its existing fast path.
        if (entry.kind != CanonicalSearchKind.RELEASE_GROUP &&
            !canonicalNeedsCoverRefresh(entry.artworkUri)) return
        canonicalDetailCoverJob = viewModelScope.launch(Dispatchers.IO) {
            val artwork = try {
                canonicalArtworkForRow(entry, canonicalNativeDetailArtworkSize(entry.kind))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null } ?: return@launch
            if (_searchSource.value != SearchSource.CANONICAL ||
                canonicalPath.lastOrNull() != entry) return@launch
            val current = (_canonicalDetail.value as? UiState.Success)?.data
                ?: return@launch
            val accepted = if (entry.kind == CanonicalSearchKind.RELEASE_GROUP)
                canonicalShouldAcceptVerifiedGroupArtwork(current.artworkUri, artwork)
            else canonicalShouldAcceptArtworkUpdate(current.artworkUri, artwork)
            if (!accepted) return@launch
            _canonicalDetail.value = UiState.Success(current.copy(
                artworkUri = artwork,
                rows = canonicalReleaseTracksWithArtwork(
                    current.rows, artwork,
                    previousInheritedArtwork = current.artworkUri,
                    inheritedFromGroup = entry.kind == CanonicalSearchKind.RELEASE_GROUP,
                ),
            ))
            val id = canonicalNativeBrowseId(entry)
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == id) it.copy(thumbnailUrl = artwork) else it
            }
            refreshCanonicalSearchRecordingAlbumArtwork()
        }
    }

    /** Cancel the old namespace before the new source starts answering the same query. */
    private fun cancelSearchSourceRequests() {
        newestRequestId.incrementAndGet()
        searchSession = null
        searchPagingJob?.cancel()
        _searchLoadingMore.value = false
        suggestPipelineJob?.cancel()
        typeaheadPipelineJob?.cancel()
        suggestPipelineJob = null
        typeaheadPipelineJob = null
        // collectLatest uses this blank request to cancel an outstanding YT call
        // without sending another query to YouTube.
        searchRequests.tryEmit(SearchRequest("", _filter.value, newestRequestId.get()))
        suggestRequests.tryEmit("")
        addonSearchJob?.cancel()
        serverSearchJob?.cancel()
        _serverResults.value = null
        canonicalSearchJob?.cancel()
        canonicalSearchCoverJob?.cancel()
        canonicalSearchAlbumFanoutJob?.cancel()
        canonicalTypeaheadJob?.cancel()
        resetCanonicalSearchPagination()
        canonicalBrowseJob?.cancel()
        canonicalPagingJob?.cancel()
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        _canonicalResults.value = null
        _addonResults.value = null
        clearCanonicalCatalogNavigation()
    }

    private fun switchSearchSource(source: SearchSource, rerunCurrentQuery: Boolean = true) {
        if (_searchSource.value == source) return
        _searchSource.value = source
        if (source == SearchSource.CANONICAL &&
            _filter.value !in setOf(
                SearchFilter.ALL, SearchFilter.SONGS,
                SearchFilter.ALBUMS, SearchFilter.ARTISTS,
            )
        ) _filter.value = SearchFilter.ALL
        cancelSearchSourceRequests()
        _results.value = null
        _searchScrollReset.value += 1
        when (source) {
            SearchSource.LIBRARY -> loadLibrarySongs()
            SearchSource.YOUTUBE,
            SearchSource.ADDONS,
            SearchSource.CANONICAL -> if (rerunCurrentQuery && _query.value.isNotBlank()) submitSearch()
        }
        if (source == SearchSource.YOUTUBE) {
            suggestPipelineJob = startSuggestPipeline()
            typeaheadPipelineJob = startTypeaheadMediaPipeline()
        }
    }

    private fun showCanonicalCatalogEntry(entry: CanonicalCatalogSearchRow) {
        canonicalDetailCoverJob?.cancel()
        canonicalBrowseJob?.cancel()
        canonicalPagingJob?.cancel()
        _canonicalLoadingMore.value = false
        _canonicalDetail.value = UiState.Loading
        _searchScrollReset.value += 1
        canonicalBrowseJob = viewModelScope.launch {
            // A card can be stale even after this group's Front-v5 has been
            // saved by the artist shelf. Read that exact verified MBID once,
            // then give the identical URL to both header and all tracks.
            // No archive request or fuzzy artist/title fallback.
            val groupArtwork = if (entry.kind == CanonicalSearchKind.RELEASE_GROUP &&
                canonicalEditorialReleaseType(entry) != null
            ) {
                try {
                    withContext(Dispatchers.IO) {
                        coverArtArchive.cachedVerifiedGroupCover(
                            entry.musicBrainzId, CoverArtSize.THUMBNAIL,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
            } else null
            val initialArtwork = canonicalVerifiedGroupDetailArtwork(entry, groupArtwork)
            if (groupArtwork != null && _searchSource.value == SearchSource.CANONICAL &&
                canonicalPath.lastOrNull() == entry
            ) {
                val pageId = canonicalNativeBrowseId(entry)
                _detailStack.update { pages -> pages.map { page ->
                    if (page.browseId == pageId &&
                        canonicalShouldAcceptVerifiedGroupArtwork(page.thumbnailUrl, groupArtwork))
                        page.copy(thumbnailUrl = groupArtwork) else page
                } }
            }
            // Reopening an already browsed artist should paint persisted
            // metadata and verified Front-v4 artwork immediately. A slow
            // MusicBrainz browse refreshes this page later; it must not hold
            // the old, valid Room catalog behind a network loading spinner.
            if (entry.kind == CanonicalSearchKind.ARTIST) {
                val saved = try {
                    withContext(Dispatchers.IO) {
                        MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                            getApplication<Application>(),
                        )).cachedArtistGroups(entry.musicBrainzId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { emptyList() }
                if (saved.isNotEmpty() &&
                    _searchSource.value == SearchSource.CANONICAL &&
                    canonicalPath.lastOrNull() == entry
                ) {
                    val cachedRows = saved.map { hit -> CanonicalCatalogSearchRow(
                        hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP, hit.title,
                        hit.artistDisplay?.takeUnless { it.equals("Other", true) },
                        releaseDate = hit.firstReleaseDate,
                        releaseKind = hit.primaryType?.takeUnless { it.equals("Other", true) },
                        secondaryTypes = hit.secondaryTypes,
                        artworkUri = hit.artworkUri,
                        fromOfflineCache = true,
                    ) }
                    val preview = CanonicalCatalogDetailPage(
                        entry.title, "Saved release groups · refreshing",
                        cachedRows,
                    )
                    _canonicalDetail.value = UiState.Success(preview)
                    val nativeId = canonicalNativeBrowseId(entry)
                    _detailStack.update { stack ->
                        stack.map { page ->
                            if (page.browseId != nativeId) page else page.copy(
                                songs = UiState.Success(emptyList()),
                                sections = canonicalNativeSections(
                                    parentKind = CanonicalSearchKind.ARTIST,
                                    rows = cachedRows,
                                    heading = getApplication<Application>().getString(
                                        R.string.shelf_new_albums_singles,
                                    ),
                                    albumsHeading = getApplication<Application>().getString(
                                        R.string.albums,
                                    ),
                                    singlesHeading = getApplication<Application>().getString(
                                        R.string.canonical_singles_eps,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            // A specific MusicBrainz Release is an exact edition identity:
            // its previously persisted Track rows can paint immediately,
            // while lookupRelease refreshes in the network branch below.
            if (entry.kind == CanonicalSearchKind.RELEASE) {
                val cachedReleasePreview = try {
                    withContext(Dispatchers.IO) {
                        val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                        val detail = canonicalMatchingCachedRelease(
                            entry.musicBrainzId,
                            MusicBrainzCatalogIndex(db).cachedRelease(entry.musicBrainzId),
                        )
                        detail?.let { cached ->
                            val assets = db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                                cached.tracks.map { it.recordingMbid },
                            ).toSet()
                            CanonicalCatalogDetailPage(
                                title = cached.title,
                                summary = "Saved release · refreshing",
                                artworkUri = entry.artworkUri,
                                rows = canonicalReleaseTracksWithArtwork(
                                    cached.tracks.map { track ->
                                        CanonicalCatalogSearchRow(
                                            musicBrainzId = track.recordingMbid,
                                            kind = CanonicalSearchKind.RECORDING,
                                            title = "${track.mediumNumber}.${track.trackNumber}  ${track.title}",
                                            subtitle = track.artistDisplay ?: cached.artistDisplay,
                                            hasCatalogedAsset = track.recordingMbid in assets,
                                            fromOfflineCache = true,
                                            trackMbid = track.trackMbid,
                                            durationMs = track.durationMs,
                                        )
                                    },
                                    entry.artworkUri,
                                ),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (cachedReleasePreview != null &&
                    _searchSource.value == SearchSource.CANONICAL &&
                    canonicalPath.lastOrNull() == entry
                ) {
                    _canonicalDetail.value = UiState.Success(cachedReleasePreview)
                    val pageId = canonicalNativeBrowseId(entry)
                    _detailStack.update { pages -> pages.map { page ->
                        if (page.browseId != pageId) page else page.copy(
                            title = cachedReleasePreview.title,
                            thumbnailUrl = cachedReleasePreview.artworkUri ?: page.thumbnailUrl,
                            songs = UiState.Success(emptyList()),
                            sections = canonicalNativeSections(
                                parentKind = entry.kind,
                                rows = cachedReleasePreview.rows,
                                heading = getApplication<Application>().getString(R.string.albums),
                            ),
                        )
                    } }
                }
            }
            var artistFirstPage: MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>? = null
            val output: UiState<CanonicalCatalogDetailPage> = when (entry.kind) {
                CanonicalSearchKind.ARTIST -> when (
                    val result = // Group-first discovery needs enough range for albums AND singles/EPs;
                    // 25 arbitrary upstream groups often omitted actual releases.
                    musicBrainzGraph.browseReleaseGroupsForArtist(entry.musicBrainzId, limit = 75)
                ) {
                    is MusicBrainzCatalogGraphResult.Success -> {
                        artistFirstPage = result.value
                        val verifiedGroups = try {
                            withContext(Dispatchers.IO) {
                                val index = MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                                    getApplication<Application>(),
                                ))
                                index.persistArtistGroups(entry.musicBrainzId, result.value.items)
                                index.cachedArtistGroups(entry.musicBrainzId)
                                    .associateBy { it.musicBrainzId }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { emptyMap() }
                        UiState.Success(CanonicalCatalogDetailPage(entry.title,
                            "Release groups · showing ${result.value.items.size} of ${result.value.total}",
                            result.value.items.map { hit -> CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP,
                                verifiedGroups[hit.musicBrainzId]?.title ?: hit.title,
                                hit.artistDisplay?.takeUnless { it.equals("Other", true) },
                                releaseDate = hit.firstReleaseDate,
                                releaseKind = hit.primaryType?.takeUnless { it.equals("Other", true) },
                                secondaryTypes = hit.secondaryTypes,
                                artworkUri = verifiedGroups[hit.musicBrainzId]?.artworkUri,
                            ) },
                            nextOffset = result.value.nextOffset,
                            totalCount = result.value.total,
                        ))
                    }
                    is MusicBrainzCatalogGraphResult.Failure -> {
                        val cached = try {
                            withContext(Dispatchers.IO) {
                                MusicBrainzCatalogIndex(
                                    CanonicalCatalogDatabase.get(getApplication<Application>()),
                                ).cachedArtistGroups(entry.musicBrainzId)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { emptyList() }
                        if (cached.isEmpty()) UiState.Error("Unable to load artist discography")
                        else UiState.Success(CanonicalCatalogDetailPage(
                            title = entry.title,
                            summary = "Saved release groups · offline catalog",
                            rows = cached.map { hit -> CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP,
                                hit.title, hit.artistDisplay?.takeUnless { it.equals("Other", true) },
                                releaseDate = hit.firstReleaseDate,
                                releaseKind = hit.primaryType?.takeUnless { it.equals("Other", true) },
                                secondaryTypes = hit.secondaryTypes,
                                artworkUri = hit.artworkUri,
                                fromOfflineCache = true,
                            ) },
                        ))
                    }
                }
                CanonicalSearchKind.RELEASE_GROUP -> {
                    // Revisits should paint an Official cached tracklist before
                    // paying for a new MB release browse and release detail.
                    // Only persisted exact ReleaseGroup → Official Release →
                    // Track relations qualify. The online branch below can
                    // refresh this preview without blocking the first paint.
                    val cachedPreview = try {
                        withContext(Dispatchers.IO) {
                            val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                            val index = MusicBrainzCatalogIndex(db)
                            val editions = index.cachedGroupReleases(entry.musicBrainzId)
                            val detail = canonicalCachedOfficialGroupTracklist(
                                entry.musicBrainzId, entry.title, editions,
                                readRelease = { index.cachedRelease(it) },
                            )
                            detail?.let { cached ->
                                val assets = db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                                    cached.tracks.map { it.recordingMbid },
                                ).toSet()
                                CanonicalCatalogDetailPage(
                                    title = canonicalOfficialGroupDisplayTitle(
                                        entry.title, editions,
                                    ),
                                    summary = null,
                                    artworkUri = initialArtwork.takeIf {
                                        canonicalEditorialReleaseType(entry) != null
                                    },
                                    rows = canonicalReleaseTracksWithArtwork(
                                        cached.tracks.map { track ->
                                            CanonicalCatalogSearchRow(
                                                musicBrainzId = track.recordingMbid,
                                                kind = CanonicalSearchKind.RECORDING,
                                                title = track.title,
                                                subtitle = track.artistDisplay ?: cached.artistDisplay,
                                                hasCatalogedAsset = track.recordingMbid in assets,
                                                fromOfflineCache = true,
                                                trackMbid = track.trackMbid,
                                                durationMs = track.durationMs,
                                            )
                                        },
                                        initialArtwork,
                                        inheritedFromGroup = true,
                                    ),
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                    if (cachedPreview != null &&
                        _searchSource.value == SearchSource.CANONICAL &&
                        canonicalPath.lastOrNull() == entry
                    ) {
                        _canonicalDetail.value = UiState.Success(cachedPreview)
                        val nativeId = canonicalNativeBrowseId(entry)
                        _detailStack.update { pages -> pages.map { page ->
                            if (page.browseId != nativeId) page else page.copy(
                                title = cachedPreview.title,
                                thumbnailUrl = cachedPreview.artworkUri ?: page.thumbnailUrl,
                                songs = UiState.Success(emptyList()),
                                sections = canonicalNativeSections(
                                    parentKind = entry.kind,
                                    rows = cachedPreview.rows,
                                    heading = getApplication<Application>().getString(R.string.albums),
                                ),
                            )
                        } }
                    }
                    // Album / Single / EP tiles are *works*, not a demand that
                    // the listener choose among every national/digital edition.
                    // Resolve the best documented Release to its native tracklist
                    // without synthesizing a Song or discarding edition MBIDs.
                    val live = musicBrainzGraph.browseGroupReleases(
                        entry.musicBrainzId, limit = 75,
                    )
                    val versions = if (live is MusicBrainzCatalogGraphResult.Success) {
                        try {
                            withContext(Dispatchers.IO) {
                                MusicBrainzCatalogIndex(CanonicalCatalogDatabase.get(
                                    getApplication<Application>(),
                                )).persistGroupEditions(entry.musicBrainzId, live.value.items)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { }
                        live.value.items
                    } else try {
                        withContext(Dispatchers.IO) {
                            MusicBrainzCatalogIndex(
                                CanonicalCatalogDatabase.get(getApplication<Application>()),
                            ).cachedGroupReleases(entry.musicBrainzId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { emptyList() }

                    // A failed group browse plus a valid local tracklist
                    // must not trigger up to four additional sequential
                    // lookupRelease network requests just to re-read Room.
                    val ranked = if (cachedPreview != null &&
                        live is MusicBrainzCatalogGraphResult.Failure
                    ) emptyList() else canonicalPreferredEditions(versions, entry.title)
                    val editorialTitle = canonicalOfficialGroupDisplayTitle(entry.title, versions)
                    var chosen: MusicBrainzCatalogReleaseDetail? = null
                    // Bounded retry: an MB edition can have no usable tracks,
                    // even when its Release Group is perfectly valid.
                    for (candidate in ranked.take(4)) {
                        val detail = musicBrainzGraph.lookupRelease(candidate.musicBrainzId)
                        if (detail is MusicBrainzCatalogGraphResult.Success &&
                            detail.value.groupMbid == entry.musicBrainzId &&
                            detail.value.tracks.isNotEmpty()
                        ) {
                            chosen = detail.value
                            break
                        }
                        val cached = try {
                            withContext(Dispatchers.IO) {
                                MusicBrainzCatalogIndex(
                                    CanonicalCatalogDatabase.get(getApplication<Application>()),
                                ).cachedRelease(candidate.musicBrainzId)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null }
                        if (cached?.groupMbid == entry.musicBrainzId &&
                            cached.tracks.isNotEmpty()
                        ) {
                            chosen = cached
                            break
                        }
                    }
                    if (chosen == null) {
                        if (cachedPreview != null) UiState.Success(cachedPreview)
                        else UiState.Error(getApplication<Application>().getString(
                            R.string.canonical_discovery_cache_empty,
                        ))
                    } else {
                        val detail = chosen
                        val available = try {
                            withContext(Dispatchers.IO) {
                                val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                                MusicBrainzCatalogIndex(db).persistReleaseDetail(detail)
                                db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                                    detail.tracks.map { it.recordingMbid },
                                ).toSet()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { emptySet() }
                        // Publish tracks immediately; remote cover is a separate,
                        // cancellable decoration, never a tracklist prerequisite.
                        UiState.Success(CanonicalCatalogDetailPage(
                            title = editorialTitle,
                            summary = null,
                            artworkUri = initialArtwork.takeIf {
                                canonicalEditorialReleaseType(entry) != null
                            },
                            rows = canonicalReleaseTracksWithArtwork(detail.tracks.map { track ->
                                CanonicalCatalogSearchRow(
                                    musicBrainzId = track.recordingMbid,
                                    kind = CanonicalSearchKind.RECORDING,
                                    title = track.title,
                                    subtitle = track.artistDisplay ?: detail.artistDisplay,
                                    hasCatalogedAsset = track.recordingMbid in available,
                                    trackMbid = track.trackMbid,
                                    durationMs = track.durationMs,
                                )
                            }, initialArtwork, inheritedFromGroup = true),
                        ))
                    }
                }
                CanonicalSearchKind.RELEASE -> when (
                    val result = musicBrainzGraph.lookupRelease(entry.musicBrainzId)
                ) {
                    is MusicBrainzCatalogGraphResult.Success -> {
                        val details = result.value
                        val available = try {
                            withContext(Dispatchers.IO) {
                                val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                                MusicBrainzCatalogIndex(db).persistReleaseDetail(details)
                                db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                                    details.tracks.map { it.recordingMbid },
                                ).toSet()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptySet()
                        }
                        UiState.Success(CanonicalCatalogDetailPage(
                            title = details.title,
                            artworkUri = entry.artworkUri,
                            summary = listOfNotNull(details.country, details.releaseDate)
                                .joinToString(" · "),
                            rows = canonicalReleaseTracksWithArtwork(details.tracks.map { track -> CanonicalCatalogSearchRow(
                                musicBrainzId = track.recordingMbid,
                                kind = CanonicalSearchKind.RECORDING,
                                title = "${track.mediumNumber}.${track.trackNumber}  ${track.title}",
                                subtitle = track.artistDisplay ?: details.artistDisplay,
                                hasCatalogedAsset = track.recordingMbid in available,
                                trackMbid = track.trackMbid,
                                durationMs = track.durationMs,
                            ) }, entry.artworkUri),
                        ))
                    }
                    is MusicBrainzCatalogGraphResult.Failure -> {
                        val cached = try {
                            withContext(Dispatchers.IO) {
                                val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                                val detail = MusicBrainzCatalogIndex(db).cachedRelease(entry.musicBrainzId)
                                val available = detail?.let {
                                    db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                                        it.tracks.map { track -> track.recordingMbid },
                                    ).toSet()
                                } ?: emptySet()
                                detail to available
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { null to emptySet<String>() }
                        val details = cached.first
                        if (details == null) UiState.Error("Unable to load release tracks")
                        else UiState.Success(CanonicalCatalogDetailPage(
                            title = details.title,
                            summary = "Saved release · offline catalog",
                            artworkUri = entry.artworkUri,
                            rows = canonicalReleaseTracksWithArtwork(details.tracks.map { track -> CanonicalCatalogSearchRow(
                                musicBrainzId = track.recordingMbid,
                                kind = CanonicalSearchKind.RECORDING,
                                title = "${track.mediumNumber}.${track.trackNumber}  ${track.title}",
                                subtitle = track.artistDisplay ?: details.artistDisplay,
                                hasCatalogedAsset = track.recordingMbid in cached.second,
                                fromOfflineCache = true,
                                trackMbid = track.trackMbid,
                                durationMs = track.durationMs,
                            ) }, entry.artworkUri),
                        ))
                    }
                }
                CanonicalSearchKind.RECORDING -> return@launch
            }
            if (_searchSource.value == SearchSource.CANONICAL && canonicalPath.lastOrNull() == entry) {
                _canonicalDetail.value = output
                val nativeId = canonicalNativeBrowseId(entry)
                _detailStack.value = _detailStack.value.map { page ->
                    if (page.browseId != nativeId) page else when (output) {
                        is UiState.Success -> page.copy(
                            title = output.data.title,
                            songs = UiState.Success(emptyList()),
                            sections = canonicalNativeSections(
                                parentKind = entry.kind,
                                rows = output.data.rows,
                                heading = getApplication<Application>().getString(
                                    if (entry.kind == CanonicalSearchKind.ARTIST) R.string.shelf_new_albums_singles
                                    else R.string.albums,
                                ),
                                albumsHeading = getApplication<Application>().getString(R.string.albums),
                                singlesHeading = getApplication<Application>().getString(R.string.canonical_singles_eps),
                            ),
                            thumbnailUrl = output.data.artworkUri ?: page.thumbnailUrl,
                        )
                        is UiState.Error -> page.copy(songs = UiState.Error(output.message))
                        UiState.Loading -> page
                    }
                }
                // Publish the complete page before starting any competing
                // asynchronous image update, including an immediate cache hit.
                if (output is UiState.Success) {
                    if (entry.kind == CanonicalSearchKind.ARTIST) {
                        enrichCanonicalNativeDetailCovers(entry)
                    } else if (entry.kind == CanonicalSearchKind.RELEASE ||
                        entry.kind == CanonicalSearchKind.RELEASE_GROUP
                    ) {
                        enrichCanonicalNativeReleaseCover(entry)
                    }
                }
            }
            // Publish the first artist page immediately. Complete the next
            // four pages progressively, still respecting MusicBrainz's global
            // rate limiter and the same cancellation/navigation boundary.
            // The remaining real cursor stays available to the existing
            // "more" action for unusually large discographies.
            val first = artistFirstPage
            if (entry.kind == CanonicalSearchKind.ARTIST && first?.nextOffset != null &&
                _searchSource.value == SearchSource.CANONICAL &&
                canonicalPath.lastOrNull() == entry
            ) {
                _canonicalLoadingMore.value = true
                try {
                MusicBrainzArtistDiscographyPager.collect(
                    first, maxAdditionalPages = 4,
                    fetch = { offset ->
                        musicBrainzGraph.browseReleaseGroupsForArtist(
                            entry.musicBrainzId, limit = 75, offset = offset)
                    },
                    onPage = { hits, next ->
                        val artworkById = try {
                            withContext(Dispatchers.IO) {
                                val idx = MusicBrainzCatalogIndex(
                                    CanonicalCatalogDatabase.get(getApplication<Application>()))
                                idx.persistArtistGroups(entry.musicBrainzId, hits)
                                idx.cachedArtistGroups(entry.musicBrainzId)
                                    .associate { it.musicBrainzId to it.artworkUri }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { emptyMap() }
                        if (_searchSource.value == SearchSource.CANONICAL &&
                            canonicalPath.lastOrNull() == entry
                        ) {
                            val current = (_canonicalDetail.value as? UiState.Success)?.data
                            if (current != null) {
                                val known = current.rows.associateBy { it.musicBrainzId }
                                val rows = hits.map { hit ->
                                    val previous = known[hit.musicBrainzId]
                                    CanonicalCatalogSearchRow(
                                        hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP,
                                        hit.title,
                                        hit.artistDisplay?.takeUnless { it.equals("Other", true) },
                                        releaseDate = hit.firstReleaseDate,
                                        releaseKind = hit.primaryType?.takeUnless {
                                            it.equals("Other", true)
                                        },
                                        secondaryTypes = hit.secondaryTypes,
                                        artworkUri = previous?.artworkUri ?:
                                            artworkById[hit.musicBrainzId],
                                    )
                                }
                                _canonicalDetail.value = UiState.Success(current.copy(
                                    rows = rows,
                                    nextOffset = next,
                                    summary = "Release groups · showing ${rows.size} of ${current.totalCount ?: rows.size}",
                                ))
                                val nativeId = canonicalNativeBrowseId(entry)
                                _detailStack.value = _detailStack.value.map { page ->
                                    if (page.browseId != nativeId) page else page.copy(
                                        sections = canonicalNativeSections(
                                            parentKind = CanonicalSearchKind.ARTIST,
                                            rows = rows,
                                            heading = getApplication<Application>().getString(
                                                R.string.shelf_new_albums_singles),
                                            albumsHeading = getApplication<Application>().getString(
                                                R.string.albums),
                                            singlesHeading = getApplication<Application>().getString(
                                                R.string.canonical_singles_eps),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                )
                if (_searchSource.value == SearchSource.CANONICAL &&
                    canonicalPath.lastOrNull() == entry
                ) enrichCanonicalNativeDetailCovers(entry)
                } finally {
                    _canonicalLoadingMore.value = false
                }
            }
        }
    }

    /** The words typed into the Addons search before, newest first. */
    val addonSearchHistory: StateFlow<List<String>> = SearchHistory.addonRecent

    fun removeAddonSearch(query: String) = SearchHistory.removeAddonQuery(query)

    fun clearAddonSearchHistory() = SearchHistory.clearAddonQueries()

    /** Every track in the Local Music folder, read when the Library source is picked. */
    private val _librarySongs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)

    /**
     * The Library source's answer for the current query: the Local Music
     * folder narrowed by the same match its own filter box uses, so the two
     * places never disagree about what is on the device. Null while the field
     * is empty — there is nothing asked yet.
     */
    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_librarySongs, _query) { state, query ->
        val term = query.trim()
        when {
            term.isEmpty() -> null
            state is UiState.Success -> UiState.Success(state.data.filter { it.matchesSearch(term) })
            else -> state
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The search pipeline's own state. Declared here, above [init], because
    // that is where the collector is started from and a property declared
    // below it would still be null when it runs. See [startSearchPipeline].

    /**
     * Buffered so an emission is never lost to a collector that happens to be
     * mid-search, and [BufferOverflow.DROP_OLDEST] because when two arrive
     * together the later one is the one meant.
     */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same arrangement as [searchRequests], for the typeahead — where the
     * drop policy earns its keep rather than just being safe: this one really
     * does take a keystroke each, and a fast typist's backlog should collapse
     * to the prefix they ended on instead of being worked through a letter at
     * a time.
     */
    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val newestRequestId = AtomicLong(0L)

    /**
     * Results of recent searches, so a query searched before is answered
     * without asking again. That covers the two ways a query is repeated most:
     * a filter tab, which re-runs the same text against a different tab and
     * then usually goes back, and a term tapped out of the recent searches.
     *
     * Its other half is [prefixMatch], which is what the typeahead makes worth
     * keeping: picking "coldplay yellow" off a list is normally preceded by
     * having searched "coldplay", and those results are close enough to leave
     * up for the moment the narrower one takes rather than blanking the page
     * to a spinner.
     */
    private data class SearchCacheEntry(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    private data class SearchSession(
        val key: String,
        val requestId: Long,
        val filter: SearchFilter,
        val continuation: String?,
    )

    private val searchCache = LruCache<String, SearchCacheEntry>(SEARCH_CACHE_ENTRIES)
    private var searchSession: SearchSession? = null

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    /** Completed hits are retained for the playing track so choosing one is instant. */
    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: Pair<String, Set<LyricsSource>>? = null

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a downloaded track had its lyrics
     * fetched once already and written into its own file (see `LyricsTag`), so
     * asking the same servers again is a round trip to arrive at a string that
     * is on disk — and one that fails outright with the connection off, which
     * is what made a downloaded song show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val sources = allowedCanonicalLyricsSources(
            requested = if (AppSettings.syncedLyrics.value) {
                AppSettings.lyricsSources.value
            } else {
                emptySet()
            },
            canonicalDiscovery = isCanonicalDiscoverySelected(),
            configs = SourceRegistry.configs.value,
        )
        val key = videoId to sources
        if (lyricsFor == key) return
        // The duration lands a beat after the track, and a database match needs
        // it. Turned away here rather than inside the job below: claiming the
        // lookup first and giving it up asynchronously means the very re-trigger
        // that carries the duration can arrive while the claim still stands, be
        // dropped as a duplicate, and leave the track marked as being looked up
        // by nobody — which is what left a paused track loading for ever, since
        // pausing is when the duration is most likely to arrive a frame late.
        if (localUri == null && durationMs <= 0L) return
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(videoId, title, artist, durationMs, album)
        selectedLyricsSource = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty() && localUri == null) {
            // No network provider remains permitted. Still try embedded
            // lyrics below when the exact local file is available.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    _lyrics.value = embedded
                    // No source to name: what the file records is the lyrics,
                    // not which of the eight services they came from months ago.
                    _lyricsSource.value = null
                    _lyricsChecked.value = true
                    return@launch
                }
            }
            if (sources.isEmpty()) {
                // Embedded lyrics were checked first. Neither the opted-out
                // YouTube endpoints nor any other provider should be called.
                _lyricsChecked.value = true
                return@launch
            }
            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album,
                allowedCanonicalLyricsSources(
                    sources, isCanonicalDiscoverySelected(), SourceRegistry.configs.value,
                ),
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result ->
                    providerFinished(generation, source, result)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            _lyrics.value = selected?.lines
            _lyricsSource.value = selected?.source
            _lyricsChecked.value = true
        }
    }

    /**
     * Selects a provider from the player drawer. Completed hits are applied
     * from memory; misses are inert; only an untouched provider goes online.
     */
    fun selectLyricsProvider(source: LyricsSource) {
        // The manual picker must not bypass the automatic provider filter.
        if (source !in allowedCanonicalLyricsSources(
                setOf(source), isCanonicalDiscoverySelected(), SourceRegistry.configs.value,
            )
        ) return
        val request = currentLyricsRequest ?: return
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                // Apply it as soon as the already-running automatic attempt completes.
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED, null -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        if (source !in allowedCanonicalLyricsSources(
                setOf(source), isCanonicalDiscoverySelected(), SourceRegistry.configs.value,
            )
        ) return
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = viewModelScope.launch {
            // Recheck inside the launched job in case opt-out raced dispatch.
            val permitted = allowedCanonicalLyricsSources(
                setOf(source), isCanonicalDiscoverySelected(), SourceRegistry.configs.value,
            )
            if (source !in permitted) return@launch
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = permitted,
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result ->
                    providerFinished(generation, provider, result)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            _lyricsProviderStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        if (selectedLyricsSource == source) showLyricsResult(result)
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        // A tap may have selected a provider while the priority race was still
        // using it. If that race then cancels the loser, honour the tap with a
        // dedicated request instead of leaving the row stuck at "Fetching".
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _lyricsSource.value = result.source
        _lyricsChecked.value = true
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()
    private var accountLoadJob: Job? = null
    private var accountReloadJob: Job? = null

    /**
     * The channels this login can act as. Empty until the picker asks for
     * them — it is one more request per sign-in and nothing else on the
     * settings page needs the answer.
     */
    private val _channels = MutableStateFlow<List<AccountChannel>>(emptyList())
    val channels: StateFlow<List<AccountChannel>> = _channels.asStateFlow()

    private val _channelsLoading = MutableStateFlow(false)
    val channelsLoading: StateFlow<Boolean> = _channelsLoading.asStateFlow()
    private var channelsLoadJob: Job? = null

    /**
     * The chosen channel's [AccountChannel.key], or null while the app is
     * acting as whichever channel YouTube Music serves by default.
     */
    private val _selectedChannelKey = MutableStateFlow(
        authStore.activeSession?.activeProfileId ?: authStore.channelPageId ?: authStore.channelDataSyncId,
    )
    val selectedChannelKey: StateFlow<String?> = _selectedChannelKey.asStateFlow()

    private val _selectedChannelName = MutableStateFlow(
        authStore.activeSession?.profiles?.firstOrNull { it.profileId == authStore.activeProfileId }?.name
            ?: authStore.channelName,
    )
    val selectedChannelName: StateFlow<String?> = _selectedChannelName.asStateFlow()

    /** Source of truth for Google sessions and their YouTube identities. */
    private val _googleAccounts = MutableStateFlow(authStore.sessions)
    val googleAccounts: StateFlow<List<GoogleAccountSession>> = _googleAccounts.asStateFlow()
    private val _activeAccountId = MutableStateFlow(authStore.activeSession?.accountId)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()
    private val _activeProfileId = MutableStateFlow(authStore.activeProfileId)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()
    private var historyLoadJob: Job? = null
    private var historyStateBeforeLoad: UiState<List<Song>>? = null

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()
    private var libraryLoadJob: Job? = null
    private var libraryRefreshJob: Job? = null
    private var libraryStateBeforeLoad: UiState<LibraryPage>? = null
    private var libraryStale = false

    enum class Feed { HOME, EXPLORE, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    /** In-memory cache is partitioned by account and profile; it is never shared. */
    private data class ListenerSnapshot(
        val account: Account?, val library: UiState<LibraryPage>,
        val history: UiState<List<Song>>, val playlists: List<UserPlaylist>,
        val owned: Map<String, Boolean>,
    )
    private val listenerCache = mutableMapOf<String, ListenerSnapshot>()

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    /** UI-side continuation fetches that can be handed to the playback service. */

    private val _releaseLibrary = MutableStateFlow<Map<String, LibraryState>>(emptyMap())

    /**
     * Library state of releases that are only a card on some page — the artist
     * page's top release — keyed by browse id. They have no page of their own on
     * the stack to carry it, so it is read off the release and kept here.
     */
    val releaseLibrary: StateFlow<Map<String, LibraryState>> = _releaseLibrary.asStateFlow()

    /** Historical YouTube release state is archived, never re-fetched. */
    fun loadReleaseLibrary(@Suppress("UNUSED_PARAMETER") browseId: String) = Unit

    /** Saving a retired provider release cannot fabricate a catalog relationship. */
    fun toggleReleaseLibrary(@Suppress("UNUSED_PARAMETER") browseId: String) = Unit

    /** Set once per launch if GitHub has a release newer than this build. */
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    // ---- Ratings, library and playlists -------------------------------------

    /**
     * Ratings this session has set, which win over whatever the library feed
     * last said.
     *
     * Kept apart from the library rather than folded into it because the two
     * answer different questions: Liked Music is what YouTube knew when the
     * page was fetched, and this is what the user has done since. Layering
     * them ([likeStatuses]) means a tap shows immediately without the library
     * having to be re-fetched, and a later refresh can't undo it.
     */
    /** Every rating known for this account: the library's, then this session's. */
    val likeStatuses: StateFlow<Map<String, LikeStatus>> =
        combine(_library, LikeState.overrides) { library, overrides ->
            val liked = (library as? UiState.Success)?.data?.likedSongs
                ?.associate { it.videoId to LikeStatus.LIKE }
                .orEmpty()
            liked + overrides
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun likeStatusOf(videoId: String): LikeStatus =
        likeStatuses.value[videoId] ?: LikeStatus.INDIFFERENT

    /** YouTube thumbs are archival state, never converted into native ratings. */
    fun setLike(
        @Suppress("UNUSED_PARAMETER") videoId: String,
        @Suppress("UNUSED_PARAMETER") status: LikeStatus,
    ) = Unit

    /** The heart: liked becomes neutral, anything else becomes liked. */
    fun toggleLike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    /** As [toggleLike], for the thumb-down. */
    fun toggleDislike(videoId: String): LikeStatus? {
        if (!requireSignIn()) return null
        val previous = likeStatusOf(videoId)
        setLike(
            videoId,
            if (previous == LikeStatus.DISLIKE) {
                LikeStatus.INDIFFERENT
            } else {
                LikeStatus.DISLIKE
            },
        )
        return previous
    }

    /** Retired provider follows/saves are not canonical pins. */
    fun toggleLibrary(@Suppress("UNUSED_PARAMETER") browseId: String) = Unit

    /** Native artist ratings belong to the canonical artist route. */
    fun toggleSubscription(@Suppress("UNUSED_PARAMETER") browseId: String) = Unit

    /**
     * The open track menu's account state, or null while it is still being
     * fetched. Only one menu can be open at a time, so one slot is enough.
     */
    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu: StateFlow<SongMenu?> = _songMenu.asStateFlow()

    private var songMenuJob: Job? = null

    /** In-flight liked-library continuation sync — see [syncLikedMusic]. */
    private var likedSyncJob: Job? = null

    /** Historical provider-only menu tokens are not fetched from YouTube. */
    fun loadSongMenu(@Suppress("UNUSED_PARAMETER") videoId: String?) {
        songMenuJob?.cancel()
        _songMenu.value = null
    }

    /** The account's own playlists, for the picker and the library tab. */
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()
    private var playlistsLoadJob: Job? = null

    /**
     * Retired account playlists remain archival. Canonical and historical
     * local playlists have their own persistent store and unified detail route.
     */
    fun loadPlaylists() {
        playlistsLoadJob?.cancel()
        _playlistsLoading.value = false
    }

    /** A retired online account playlist cannot be edited through YouTube. */
    fun addToPlaylists(
        playlists: List<UserPlaylist>,
        @Suppress("UNUSED_PARAMETER") song: Song,
        onResult: (added: Int, alreadyThere: Int, failed: Int) -> Unit = { _, _, _ -> },
    ) {
        onResult(0, 0, playlists.size)
    }

    /** New playlists are local, even when an archived Google login exists. */
    fun createPlaylist(
        title: String,
        @Suppress("UNUSED_PARAMETER") privacy: PlaylistPrivacy,
        song: Song? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        viewModelScope.launch {
            com.music.bitchord.data.spotify.LocalPlaylistStore.savePlaylist(name, listOfNotNull(song))
        }
    }

    /**
     * Preserve historical IDs and ordering in LocalPlaylistStore. Never send
     * an API request to an account whose music backend was removed.
     */
    fun createPlaylistWithVideoIds(
        title: String,
        @Suppress("UNUSED_PARAMETER") privacy: PlaylistPrivacy,
        @Suppress("UNUSED_PARAMETER") videoIds: List<String>,
        songs: List<Song> = emptyList(),
        onResult: ((browseId: String?, title: String, savedLocally: Boolean) -> Unit)? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        viewModelScope.launch {
            val local = com.music.bitchord.data.spotify.LocalPlaylistStore.savePlaylist(name, songs)
            onResult?.invoke(local.browseId, local.title, true)
        }
    }

    /** Historical online playlist edits require a provider that no longer exists. */
    fun removeFromPlaylist(
        @Suppress("UNUSED_PARAMETER") browseId: String,
        @Suppress("UNUSED_PARAMETER") song: Song,
    ) = Unit

    fun addSuggestedSong(
        @Suppress("UNUSED_PARAMETER") browseId: String,
        @Suppress("UNUSED_PARAMETER") song: Song,
    ) = Unit

    fun renamePlaylist(
        @Suppress("UNUSED_PARAMETER") playlist: UserPlaylist,
        @Suppress("UNUSED_PARAMETER") title: String,
    ) = Unit

    fun loadPlaylistEntries(
        @Suppress("UNUSED_PARAMETER") playlist: UserPlaylist,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        onResult(Result.failure(UnsupportedOperationException("Retired online playlist")))
    }

    fun reorderPlaylist(
        @Suppress("UNUSED_PARAMETER") playlist: UserPlaylist,
        @Suppress("UNUSED_PARAMETER") original: List<Song>,
        @Suppress("UNUSED_PARAMETER") reordered: List<Song>,
        onResult: (Boolean) -> Unit = {},
    ) { onResult(false) }

    fun deletePlaylist(@Suppress("UNUSED_PARAMETER") playlist: UserPlaylist) = Unit

    fun editablePlaylist(@Suppress("UNUSED_PARAMETER") browseId: String?): UserPlaylist? = null

    private val _playlistOwned = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playlistOwned: StateFlow<Map<String, Boolean>> = _playlistOwned.asStateFlow()

    fun resolvePlaylistOwnership(@Suppress("UNUSED_PARAMETER") browseId: String?) = Unit

    /** True only when a locally verified or archived response supplies ownership. */
    private fun setPlaylistOwned(browseId: String, owned: Boolean) {
        _playlistOwned.value = _playlistOwned.value + (browseId to owned)
    }

    private fun requireSignIn(): Boolean = _signedIn.value

    /** Call when the library tab becomes visible. */
    fun onLibraryShown() {
        loadPlaylists()
        if (!isYouTubeSourceEnabled(SourceRegistry.configs.value)) {
            if (_library.value is UiState.Loading) {
                _library.value = UiState.Error(text(R.string.legacy_library_youtube_disabled))
            }
            return
        }
        if (_signedIn.value && _library.value is UiState.Loading && libraryLoadJob?.isActive != true) {
            loadLibrary()
            return
        }
        if (!libraryStale) return
        libraryStale = false
        if (_library.value is UiState.Success) refresh(Feed.LIBRARY)
    }

    private fun isCanonicalDiscoverySelected(): Boolean = shouldUseCanonicalDiscovery(
        explicitlyEnabled = AppSettings.canonicalDiscoveryEnabled.value,
        configs = SourceRegistry.configs.value,
    )

    /** Canonical opt-in is a metadata boundary even while playback YT remains enabled. */
    private fun canRunLegacyYoutubeCatalogLookup(): Boolean =
        shouldRunLegacyYoutubeVersionLookup(
            canonicalMode = isCanonicalDiscoverySelected(),
            configs = SourceRegistry.configs.value,
        )

    private fun legacyDiscoveryGate() = LegacyDiscoveryRequestGate(
        useCanonicalDiscovery = isCanonicalDiscoverySelected(),
    )

    /**
     * Fetch community popularity only for already known, canonically identified
     * recordings, and only when Explore actually requests it. Public popularity
     * never changes personal Em alta, MediaAsset availability, or ratings.
     */
    /**
     * Public ListenBrainz sitewide week chart. It is only an identity ranking;
     * rendering later intersects with actual MusicBrainz catalog rows.
     * Missing, expired and unavailable data never become invented trends.
     */
    /**
     * This is intentionally separate from the public community chart.
     * Only an enabled ListenBrainz account is consulted, and only MusicBrainz
     * independently verified recordings become metadata-only Explore cards.
     */
    fun loadCanonicalPersonalRecommendations() {
        val token = AppSettings.listenBrainzToken.value.takeIf {
            AppSettings.scrobblingAvailable && AppSettings.listenBrainzEnabled.value &&
                it.isNotBlank()
        }
        val account = token?.let(::canonicalRecommendationAccountKey)
        if (account != personalRecommendationAccountDigest) {
            personalRecommendationEpoch.next()
            personalRecommendationJob?.cancel()
            personalRecommendationJob = null
            _canonicalExplorePersonalRecommendations.value = CanonicalPersonalRecommendationState()
            personalRecommendationAccountDigest = account
            personalRecommendationRetryAtMs = 0L
        }
        if (token == null) return
        val now = System.currentTimeMillis()
        if (canonicalPersonalRecommendationIsFresh(
                _canonicalExplorePersonalRecommendations.value, now,
            ) || personalRecommendationJob?.isActive == true ||
            now < personalRecommendationRetryAtMs
        ) return

        val generation = personalRecommendationEpoch.next()
        personalRecommendationJob?.cancel()
        _canonicalExplorePersonalRecommendations.value = CanonicalPersonalRecommendationState()
        personalRecommendationJob = viewModelScope.launch(Dispatchers.IO) {
            val stillCurrent = {
                personalRecommendationEpoch.isCurrent(generation) &&
                    AppSettings.scrobblingAvailable &&
                    AppSettings.listenBrainzEnabled.value &&
                    AppSettings.listenBrainzToken.value == token
            }
            val response = try {
                personalRecommendationClient.recordings(token, count = 16)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ListenBrainzRecommendationFetchResult.Unavailable
            }
            if (!stillCurrent()) return@launch
            val success = response as? ListenBrainzRecommendationFetchResult.Success
            if (success == null) {
                personalRecommendationRetryAtMs = System.currentTimeMillis() + 15L * 60_000L
                return@launch
            }
            val verified = mutableMapOf<String, RecordingMetadataEnrichment>()
            val candidates = success.items.take(8)
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                if (!stillCurrent()) return@launch
                val id = candidate.recordingMbid
                val cached = artistTopMetadataCache[id]
                val metadata = if (cached != null) cached else try {
                    (withTimeoutOrNull(16_000L) {
                        artistTopMetadataProvider.lookupRecording(id)
                    } as? MetadataLookupResult.Success)?.value
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (metadata != null && metadata.musicBrainzId.equals(id, ignoreCase = true)) {
                    if (artistTopMetadataCache.size >= 64) artistTopMetadataCache.clear()
                    artistTopMetadataCache[id] = metadata
                    verified[id] = metadata
                    val rows = canonicalVerifiedPersonalRecommendationRows(candidates, verified)
                    if (stillCurrent()) {
                        _canonicalExplorePersonalRecommendations.value =
                            CanonicalPersonalRecommendationState(
                                accountDigest = account,
                                recordings = success.items,
                                verifiedRows = rows,
                                upstreamUpdatedSeconds = success.upstreamUpdatedSeconds,
                            )
                    }
                }
            }
            if (stillCurrent()) {
                val rows = canonicalVerifiedPersonalRecommendationRows(candidates, verified)
                _canonicalExplorePersonalRecommendations.value =
                    CanonicalPersonalRecommendationState(
                        accountDigest = account,
                        recordings = success.items,
                        verifiedRows = rows,
                        fetchedAtMs = success.fetchedAtMs.takeIf { rows.isNotEmpty() },
                        upstreamUpdatedSeconds = success.upstreamUpdatedSeconds,
                    )
                personalRecommendationRetryAtMs =
                    if (rows.isEmpty()) System.currentTimeMillis() + 15L * 60_000L else 0L
            }
        }
    }

    fun loadCanonicalExploreSitewide() {
        val now = System.currentTimeMillis()
        if (canonicalSitewideDiscoveryIsFresh(_canonicalExploreSitewide.value, now) ||
            sitewideJob?.isActive == true || now < sitewideRetryAfterMs
        ) return
        val generation = sitewideEpoch.next()
        sitewideJob?.cancel()
        _canonicalExploreSitewide.value = CanonicalSitewideDiscoveryState()
        sitewideJob = viewModelScope.launch(Dispatchers.IO) {
            val response = try {
                listenBrainzPopularityClient.sitewideRecordings(
                    ListenBrainzSitewideRange.WEEK, count = 25,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ListenBrainzSitewideFetchResult.Unavailable
            }
            if (!sitewideEpoch.isCurrent(generation)) return@launch
            val success = response as? ListenBrainzSitewideFetchResult.Success
            if (success == null) {
                sitewideRetryAfterMs = System.currentTimeMillis() + 5L * 60_000L
                return@launch
            }
            sitewideRetryAfterMs = 0L
            val snapshot = CanonicalSitewideDiscoveryState(
                range = success.range,
                recordings = success.recordings,
                fetchedAtMs = success.fetchedAtMs,
                upstreamUpdatedAtSeconds = success.lastUpdatedSeconds,
            )
            _canonicalExploreSitewide.value = snapshot
            // If ListenBrainz's own snapshot is undated or too old, never
            // spend MusicBrainz requests or advertise it as a weekly chart.
            if (!canonicalSitewideDiscoveryIsFresh(snapshot, System.currentTimeMillis())) {
                // The public client uses a short negative TTL for undated
                // or outdated charts. Retry at that boundary, not an hour later.
                sitewideRetryAfterMs = System.currentTimeMillis() + 15L * 60_000L
                return@launch
            }
            // Existing catalog rows already carry MusicBrainz identity; only
            // missing top entries need independent direct MB recording reads.
            val inCatalog = canonicalPublicPopularityCandidates(
                (canonicalDiscoveryCatalog.value as? UiState.Success)?.data.orEmpty(),
                limit = 100,
            ).toSet()
            val independentlyVerified = mutableMapOf<String, RecordingMetadataEnrichment>()
            for (entry in success.recordings.take(8)) {
                currentCoroutineContext().ensureActive()
                if (!sitewideEpoch.isCurrent(generation)) return@launch
                val mbid = entry.recordingMbid
                if (mbid in inCatalog) continue
                val cached = artistTopMetadataCache[mbid]
                val metadata = if (cached != null) cached else try {
                    (withTimeoutOrNull(12_000L) {
                        artistTopMetadataProvider.lookupRecording(mbid)
                    } as? MetadataLookupResult.Success)?.value
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (metadata != null && metadata.musicBrainzId.equals(mbid, ignoreCase = true)) {
                    if (artistTopMetadataCache.size >= 64) artistTopMetadataCache.clear()
                    artistTopMetadataCache[mbid] = metadata
                    independentlyVerified[mbid] = metadata
                    val rows = canonicalVerifiedSitewideRows(
                        success.recordings, independentlyVerified,
                    )
                    if (sitewideEpoch.isCurrent(generation)) {
                        _canonicalExploreSitewide.value = snapshot.copy(verifiedRows = rows)
                    }
                }
            }
        }
    }

    fun loadCanonicalExplorePublicPopularity(rows: List<CanonicalCatalogSearchRow>) {
        val ids = canonicalPublicPopularityCandidates(rows)
        val previous = explorePublicPopularityFreshBatch
        if (canonicalPublicPopularityCacheIsFresh(
                requested = ids, previous = previous?.first,
                fetchedAtMs = previous?.second, nowMs = System.currentTimeMillis(),
            ) || (ids.isNotEmpty() && ids == explorePublicPopularityLoadingBatch &&
                explorePublicPopularityJob?.isActive == true)
        ) return
        val generation = explorePublicPopularityEpoch.next()
        explorePublicPopularityJob?.cancel()
        explorePublicPopularityLoadingBatch = if (ids.isEmpty()) null else ids
        if (ids.isEmpty()) {
            explorePublicPopularityFreshBatch = null
            _canonicalExplorePublicPopularity.value = emptyList()
            return
        }
        // A changed MBID set invalidates previous ranks. For the exact same,
        // fresh batch the fast path above retains UI and avoids repeated calls.
        _canonicalExplorePublicPopularity.value = emptyList()
        explorePublicPopularityJob = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                listenBrainzPopularityClient.recordingPopularity(ids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ListenBrainzPopularityFetchResult.Unavailable
            }
            if (explorePublicPopularityEpoch.isCurrent(generation)) {
                val success = result as? ListenBrainzPopularityFetchResult.Success
                explorePublicPopularityFreshBatch =
                    success?.let { ids to it.fetchedAtMs }
                explorePublicPopularityLoadingBatch = null
                _canonicalExplorePublicPopularity.value = success?.observations.orEmpty()
            }
        }
    }

    /**
     * Only a canonical artist detail requests these public rankings. ListenBrainz
     * establishes the ordering; the existing MusicBrainz client must independently
     * prove both recording identity and artist credit before a row becomes visible.
     * No listen is submitted and no MediaAsset or playback identity is generated.
     */
    fun loadCanonicalArtistPublicTop(artistMbid: String?) {
        val id = artistMbid?.lowercase(Locale.ROOT)?.takeIf {
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                .matches(it)
        }
        if (id != null && (
            canonicalArtistPublicTopIsFresh(
                id, _canonicalArtistPublicTop.value, System.currentTimeMillis(),
            ) || (_canonicalArtistPublicTop.value.artistMbid == id &&
                artistPublicTopJob?.isActive == true)
        )) return
        val generation = artistPublicTopEpoch.next()
        artistPublicTopJob?.cancel()
        _canonicalArtistPublicTop.value = CanonicalArtistPublicTopState(artistMbid = id)
        if (id == null) return
        artistPublicTopJob = viewModelScope.launch(Dispatchers.IO) {
            val popularity = try {
                listenBrainzPopularityClient.topRecordingsForArtist(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ListenBrainzArtistTopFetchResult.Unavailable
            }
            val success = popularity as? ListenBrainzArtistTopFetchResult.Success
            val ranked = success?.recordings?.take(8).orEmpty()
            if (success != null && ranked.isEmpty() && artistPublicTopEpoch.isCurrent(generation) &&
                _detailStack.value.lastOrNull()?.browseId
                    ?.equals("catalog:ARTIST:$id", ignoreCase = true) == true
            ) {
                _canonicalArtistPublicTop.value = CanonicalArtistPublicTopState(
                    artistMbid = id, fetchedAtMs = success.fetchedAtMs,
                )
            }
            val verified = mutableMapOf<String, RecordingMetadataEnrichment>()
            for (candidate in ranked) {
                currentCoroutineContext().ensureActive()
                // Do not spend eight MusicBrainz requests on a page that has
                // already been closed. A partial result is not marked fresh,
                // so returning to the artist can resume from warm metadata.
                if (_detailStack.value.lastOrNull()?.browseId
                        ?.equals("catalog:ARTIST:$id", ignoreCase = true) != true
                ) break
                val mbid = candidate.recordingMbid
                val known = artistTopMetadataCache[mbid]
                val record = if (known != null) known else try {
                    val result = withTimeoutOrNull(16_000L) {
                        artistTopMetadataProvider.lookupRecording(mbid)
                    }
                    (result as? MetadataLookupResult.Success)?.value
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (record != null && record.musicBrainzId.equals(mbid, ignoreCase = true)) {
                    if (artistTopMetadataCache.size >= 64) artistTopMetadataCache.clear()
                    artistTopMetadataCache[mbid] = record
                    verified[mbid] = record
                }
                // Progressively publish only independently verified rows. Old
                // requests cannot repaint another artist after navigation.
                if (artistPublicTopEpoch.isCurrent(generation) &&
                    _detailStack.value.lastOrNull()?.browseId
                        ?.equals("catalog:ARTIST:$id", ignoreCase = true) == true
                ) {
                    val rows = canonicalVerifiedArtistTopRows(id, ranked, verified)
                    _canonicalArtistPublicTop.value = CanonicalArtistPublicTopState(
                        artistMbid = id,
                        rows = rows,
                        // Progressive rows are provisional until the bounded
                        // MusicBrainz verification pass has completed.
                    )
                }
            }
            if (artistPublicTopEpoch.isCurrent(generation) &&
                _detailStack.value.lastOrNull()?.browseId
                    ?.equals("catalog:ARTIST:$id", ignoreCase = true) == true
            ) {
                val rows = canonicalVerifiedArtistTopRows(id, ranked, verified)
                _canonicalArtistPublicTop.value = CanonicalArtistPublicTopState(
                    artistMbid = id,
                    rows = rows,
                    // Empty metadata after a nonempty public chart can reflect
                    // a transient MusicBrainz outage, so allow a later retry.
                    fetchedAtMs = success?.fetchedAtMs?.takeIf { rows.isNotEmpty() },
                )
            }
        }
    }

    /**
     * Query current first-release dates globally, independently of local
     * recordings. A brief absence is not a permanent empty-catalog state.
     * Keep the most recent verified snapshot when the network is unavailable.
     */
    fun loadCanonicalWorldwideReleases(force: Boolean = false) {
        if (worldwideReleaseJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && (now < worldwideReleaseRetryAtMs ||
                (worldwideReleaseFetchedAtMs > 0L &&
                    now - worldwideReleaseFetchedAtMs < 6L * 60L * 60L * 1000L)
            )
        ) return
        val generation = worldwideReleaseEpoch.next()
        _canonicalWorldwideLoading.value = true
        worldwideReleaseJob = viewModelScope.launch(Dispatchers.IO) {
            val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            val until = today.toString()
            val rows = try {
                val narrowFrom = today.minusDays(30).toString()
                val wideFrom = today.minusDays(90).toString()
                val recent = musicBrainzSearch.searchRecentReleaseGroups(
                    narrowFrom, until, limit = 100,
                )
                val recentHits =
                    (recent as? MusicBrainzCatalogSearchResult.Success)?.page?.items.orEmpty()
                val recentRows = canonicalPublicReleaseRows(recentHits, narrowFrom, until)
                // A nonempty raw search result can consist entirely of
                // uncredited, untyped or unsafe editions. Widen only after
                // applying the same eligibility rules as the visible shelf.
                if (recentRows.isNotEmpty()) recentRows else {
                    val expanded = musicBrainzSearch.searchRecentReleaseGroups(
                        wideFrom, until, limit = 100,
                    )
                    val expandedHits =
                        (expanded as? MusicBrainzCatalogSearchResult.Success)?.page?.items.orEmpty()
                    canonicalPublicReleaseRows(expandedHits, wideFrom, until)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { emptyList() }
            if (!worldwideReleaseEpoch.isCurrent(generation)) return@launch
            if (rows.isEmpty()) {
                worldwideReleaseRetryAtMs = System.currentTimeMillis() + 15L * 60_000L
                _canonicalWorldwideLoading.value = false
                return@launch
            }
            _canonicalWorldwideReleases.value = rows
            _canonicalWorldwideLoading.value = false
            worldwideReleaseFetchedAtMs = System.currentTimeMillis()
            worldwideReleaseRetryAtMs = 0L

            // Covers must be verified by their own group / official edition;
            // the MusicBrainz search result itself has no trusted artwork.
            val permits = Semaphore(2)
            canonicalWorldwideArtworkPriority(rows, until).forEach { row ->
                launch {
                    permits.withPermit {
                        val cover = try { canonicalArtworkForRow(row) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        if (cover != null && worldwideReleaseEpoch.isCurrent(generation)) {
                            _canonicalWorldwideReleases.update { old ->
                                old.map { current ->
                                    if (current.musicBrainzId == row.musicBrainzId &&
                                        canonicalNeedsCoverRefresh(current.artworkUri)
                                    ) current.copy(artworkUri = cover)
                                    else current
                                }
                            }
                        }
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (worldwideReleaseEpoch.isCurrent(generation)) {
                    _canonicalWorldwideLoading.value = false
                }
            }
        }
    }

    /** Explore genre requests use actual MusicBrainz tags, not YouTube pages. */
    fun loadCanonicalGenre(tag: String) {
        val selected = canonicalExploreGenres.firstOrNull { it.tag == tag } ?: return
        val generation = canonicalGenreEpoch.next()
        canonicalGenreJob?.cancel()
        _canonicalGenreResults.value = UiState.Loading
        canonicalGenreJob = viewModelScope.launch(Dispatchers.IO) {
            val response = try { musicBrainzSearch.searchReleaseGroupsForTag(selected.tag, 60) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (!canonicalGenreEpoch.isCurrent(generation)) return@launch
            val hits = (response as? MusicBrainzCatalogSearchResult.Success)?.page?.items
            if (hits == null) {
                _canonicalGenreResults.value = UiState.Error(
                    "Não foi possível carregar o gênero. Tente novamente.",
                )
                return@launch
            }
            val rows = canonicalPublicReleaseRows(
                hits, "1900-01-01",
                java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),
            )
            _canonicalGenreResults.value = UiState.Success(rows)
            val permits = Semaphore(2)
            val todayIso = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
            canonicalGenreArtworkPriority(selected, rows, todayIso).forEach { row ->
                launch {
                    permits.withPermit {
                        val artwork = try { canonicalArtworkForRow(row) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        if (artwork != null && canonicalGenreEpoch.isCurrent(generation)) {
                            _canonicalGenreResults.update { state ->
                                if (state !is UiState.Success) state
                                else UiState.Success(state.data.map {
                                    if (it.musicBrainzId == row.musicBrainzId)
                                        it.copy(artworkUri = artwork) else it
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    fun loadCanonicalDiscoveryCatalog() {
        if (!isCanonicalDiscoverySelected()) return
        if (canonicalDiscoveryCatalogJob?.isActive == true) return
        val generation = canonicalDiscoveryCatalogEpoch.next()
        canonicalDiscoveryCatalogJob?.cancel()
        canonicalHomeCoverJob?.cancel()
        _canonicalDiscoveryCatalog.value = UiState.Loading
        canonicalDiscoveryCatalogJob = viewModelScope.launch(Dispatchers.IO) {
            val state = try {
                val dao = CanonicalCatalogDatabase.get(getApplication<Application>()).catalogDao()
                val recordings = dao.searchKnownMusicBrainzRecordings("", limit = 100)
                val available = dao.musicBrainzRecordingMbidsWithAssets(
                    recordings.map { it.musicBrainzId },
                ).toSet()
                val rows = buildList {
                    dao.searchKnownMusicBrainzArtists("", limit = 12).forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            hit.musicBrainzId, CanonicalSearchKind.ARTIST, hit.name,
                            hit.disambiguation ?: hit.country, fromOfflineCache = true,
                        ))
                    }
                    dao.searchKnownMusicBrainzGroups("", limit = 300).forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP, hit.title,
                            hit.artistDisplay ?: hit.primaryType, fromOfflineCache = true,
                            releaseDate = hit.firstReleaseDate, releaseKind = hit.primaryType,
                            secondaryTypes = hit.secondaryTypes,
                            artworkUri = hit.artworkUri,
                        ))
                    }
                    dao.searchKnownMusicBrainzReleases("", limit = 300)
                        .filter(::isCuratedMusicBrainzRelease).forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            hit.musicBrainzId, CanonicalSearchKind.RELEASE, hit.title,
                            listOfNotNull(hit.artistDisplay, hit.country, hit.releaseDate)
                                .joinToString(" · "),
                            fromOfflineCache = true, releaseDate = hit.releaseDate,
                            artworkUri = hit.artworkUri,
                        ))
                    }
                    recordings.filter(::isCuratedMusicBrainzRecording).forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            hit.musicBrainzId, CanonicalSearchKind.RECORDING, hit.title,
                            hit.artistDisplay,
                            hasCatalogedAsset = hit.musicBrainzId in available,
                            fromOfflineCache = true,
                        ))
                    }
                }.distinctBy { it.kind to it.musicBrainzId }
                UiState.Success(rows)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UiState.Error(text(R.string.canonical_discovery_cache_error))
            }
            // Keep the catalog in Loading until independent local + Server
            // candidates have been read. Publishing an empty metadata cache
            // first used to flash a false "no catalog" screen on YT opt-out,
            // even when available server audio was about to populate Home.
            // MediaStore is scanned off-main and independent of the MusicBrainz
            // cache: a fresh YT-off installation must not render a blank Home
            // just because no public MBID has been looked up yet.
            val app = getApplication<Application>()
            val picks = try {
                val local = if (LocalMediaRepository.hasStoragePermission(app)) {
                    LocalMediaRepository.getLocalMusic(app)
                } else emptyList()
                val downloaded = Downloads.getDownloadedSongs(app)
                (local + downloaded)
                    .filter { com.music.bitchord.ui.isPlayableLocalPick(it) }
                    .distinctBy { it.localUri }
                    .take(500)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (canonicalDiscoveryCatalogEpoch.isCurrent(generation) && isCanonicalDiscoverySelected()) {
                _canonicalHomeLocalPicks.value = picks
            }

            // A valid Server MediaAsset can populate "Escolha a dedo" even when
            // MediaStore is empty and MusicBrainz has not been searched yet.
            // This DAO projection omits stream URLs and provider-native IDs.
            val remotePicks = try {
                CanonicalCatalogDatabase.get(app).catalogDao()
                    .searchCanonicalRemoteServerAssets("", limit = 400)
                    .distinctBy { it.providerKey to it.mediaAssetId }
                    .map { row ->
                        CanonicalServerSearchRow(
                            recordingId = row.recordingId,
                            mediaAssetId = row.mediaAssetId,
                            providerKey = row.providerKey,
                            title = row.title,
                            artistDisplay = row.artistDisplay,
                            artworkUri = row.artworkUri,
                        )
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (canonicalDiscoveryCatalogEpoch.isCurrent(generation) &&
                isCanonicalDiscoverySelected()
            ) {
                _canonicalHomeRemotePicks.value = remotePicks
                _canonicalDiscoveryCatalog.value = state
            }

            val popularMbids = try {
                val listening = ListeningEventDatabase.get(app).listeningEventDao()
                val dao = CanonicalCatalogDatabase.get(app).catalogDao()
                val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                val popular = listening.recentPopularRecordings(
                    sinceEpochMs = since, limit = 40,
                )
                // The local Room join is by canonical RecordingId and actual
                // persisted URI, never by artist/title/legacy YouTube handle.
                val localByRecording = dao.localRows(
                    localProvider = "local-mediastore",
                    youtubeProvider = "youtube", recordingEntityType = "recording",
                ).groupBy { it.recordingId }
                val songsByUri = picks.associateBy { it.localUri }
                val trendingLocal = popular.mapNotNull { entry ->
                    localByRecording[entry.recordingId].orEmpty()
                        .firstNotNullOfOrNull { asset ->
                            songsByUri[asset.uri]?.takeIf {
                                com.music.bitchord.ui.isPlayableLocalPick(it)
                            }
                        }
                }.distinctBy { it.localUri }
                if (canonicalDiscoveryCatalogEpoch.isCurrent(generation) &&
                    isCanonicalDiscoverySelected()
                ) {
                    _canonicalTrendingLocalSongs.value = trendingLocal
                }
                popular.mapNotNull { entry ->
                    dao.externalIdentitiesForEntity(
                        provider = "musicbrainz", entityType = "recording",
                        entityId = entry.recordingId,
                    ).firstOrNull()?.externalId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (canonicalDiscoveryCatalogEpoch.isCurrent(generation) && isCanonicalDiscoverySelected()) {
                _canonicalTrendingRecordingMbids.value = popularMbids
                // The alphabetical cached Recording search is only a browse
                // preview. A well-played Recording can lie outside that page.
                // Add it from the EXISTING trusted recording/MBID claim only;
                // never infer identity by matching title or artist.
                val indexed = (state as? UiState.Success)?.data.orEmpty()
                val seen = indexed.mapTo(HashSet()) { it.kind to it.musicBrainzId }
                val trendingRows = try {
                    val dao = CanonicalCatalogDatabase.get(app).catalogDao()
                    val available = dao.musicBrainzRecordingMbidsWithAssets(popularMbids).toSet()
                    popularMbids
                        .filter { it in available && (CanonicalSearchKind.RECORDING to it) !in seen }
                        .mapNotNull { mbid ->
                            val id = dao.entityIdForExternalIdentity("musicbrainz", "recording", mbid)
                                ?: return@mapNotNull null
                            val recording = dao.recordingById(id) ?: return@mapNotNull null
                            CanonicalCatalogSearchRow(
                                musicBrainzId = mbid, kind = CanonicalSearchKind.RECORDING,
                                title = recording.title, subtitle = recording.artistDisplay,
                                hasCatalogedAsset = true, fromOfflineCache = true,
                            )
                        }.toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                if (canonicalDiscoveryCatalogEpoch.isCurrent(generation) &&
                    isCanonicalDiscoverySelected() && state is UiState.Success
                ) {
                    _canonicalDiscoveryCatalog.value = UiState.Success(
                        (indexed + trendingRows).distinctBy { it.kind to it.musicBrainzId },
                    )
                }
            }
            if (canonicalDiscoveryCatalogEpoch.isCurrent(generation) &&
                isCanonicalDiscoverySelected()
            ) enrichCanonicalHomeCovers(generation)
        }
    }

    private fun onCanonicalDiscoveryModeChanged(enabled: Boolean) {
        _usesCanonicalDiscovery.value = enabled
        cancelDiscoveryRequests()
        _home.value = UiState.Loading
        _explore.value = UiState.Loading
        _moodGenreShelves.value = UiState.Loading
        _selectedMoodGenre.value = null
        if (enabled) loadCanonicalDiscoveryCatalog()
        loadHome()
        loadExplore()
    }

    /** Stop all work owned by the old mode and invalidate responses already on the wire. */
    private fun cancelDiscoveryRequests() {
        homeLoadJob?.cancel()
        homePagingJob?.cancel()
        homeRefreshJob?.cancel()
        exploreLoadJob?.cancel()
        exploreRefreshJob?.cancel()
        moodGenreRequestJob?.cancel()
        moodGenreArtworkJob?.cancel()
        canonicalDiscoveryCatalogJob?.cancel()
        homeLoadGeneration.invalidateAndNext()
        exploreLoadGeneration.invalidateAndNext()
        canonicalDiscoveryCatalogEpoch.invalidateAndNext()
        homeContinuation = null
        homeSeenTitles.clear()
        _homeLoadingMore.value = false
        _homePendingShelves.value = 0
        _homeRecentlyPlayedLoading.value = false
        _refreshing.value = _refreshing.value - Feed.HOME - Feed.EXPLORE
    }

    /** Stop account/library prefetch after YouTube is opted out, preserving cached rows. */
    private fun cancelLegacyYouTubeAccountRequests() {
        accountLoadJob?.cancel()
        accountReloadJob?.cancel()
        libraryLoadJob?.cancel()
        libraryRefreshJob?.cancel()
        playlistsLoadJob?.cancel()
        channelsLoadJob?.cancel()
        likedSyncJob?.cancel()
        historyLoadJob?.cancel()
        if (_library.value is UiState.Loading) {
            _library.value = libraryStateBeforeLoad
                ?.takeUnless { it is UiState.Loading }
                ?: UiState.Error(text(R.string.legacy_library_youtube_disabled))
        }
        libraryStateBeforeLoad = null
        if (_history.value is UiState.Loading) {
            historyStateBeforeLoad?.let { _history.value = it }
        }
        historyStateBeforeLoad = null
        libraryStale = true
        _playlistsLoading.value = false
        _channelsLoading.value = false
        _refreshing.value = _refreshing.value - Feed.LIBRARY
    }

    init {
        startSearchPipeline()
        if (_searchSource.value == SearchSource.YOUTUBE) {
            suggestPipelineJob = startSuggestPipeline()
            typeaheadPipelineJob = startTypeaheadMediaPipeline()
        }
        viewModelScope.launch {
            combine(
                AppSettings.canonicalDiscoveryEnabled,
                SourceRegistry.configs,
            ) { explicitlyEnabled, configs -> explicitlyEnabled to configs }
                .distinctUntilChanged().collect { (explicitlyEnabled, configs) ->
                if (!isYouTubeSourceEnabled(configs)) {
                    cancelLegacyYouTubeAccountRequests()
                }
                val enabled = shouldUseCanonicalDiscovery(explicitlyEnabled, configs)
                if (_usesCanonicalDiscovery.value != enabled) {
                    onCanonicalDiscoveryModeChanged(enabled)
                }
                val nextSearchSource = if (searchSourceExplicitlySelected) {
                    effectiveSearchSource(_searchSource.value, configs)
                } else {
                    defaultDiscoverySearchSource(explicitlyEnabled, configs)
                }
                if (nextSearchSource != _searchSource.value) {
                    switchSearchSource(nextSearchSource)
                }
            }
        }
        loadHome()
        loadExplore()
        if (shouldPrefetchLegacyYouTubeAccountData(
                signedIn = _signedIn.value,
                canonicalDiscoveryEnabled = AppSettings.canonicalDiscoveryEnabled.value,
                configs = SourceRegistry.configs.value,
            )
        ) {
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
        viewModelScope.launch {
            BoundedContextRequests.snapshot.collect { snapshot ->
                snapshot?.let(::applyBoundedContextSnapshot)
            }
        }
        viewModelScope.launch {
            AppSettings.filterNonMusicAudio.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
        viewModelScope.launch {
            // Emptied from Settings while the folder sits open underneath.
            AudioCache.contentsChanged.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == CACHE_FOLDER_BROWSE_ID }) {
                    reloadLocalDetail(CACHE_FOLDER_BROWSE_ID)
                }
            }
        }
        viewModelScope.launch {
            AppSettings.webdavUrl.drop(1).collect {
                reloadRemoteDetail(com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
        viewModelScope.launch {
            combine(
                AppSettings.smbHost,
                AppSettings.smbShare,
                AppSettings.smbBasePath,
                AppSettings.smbUsername,
                AppSettings.smbPassword,
            ) { fields -> fields.toList() }
                .drop(1)
                .debounce(300)
                .collect { reloadRemoteDetail(com.music.bitchord.data.smb.SmbConfig.BROWSE_ID) }
        }
        viewModelScope.launch {
            // A leftover APK only means "Install Now" for the session that
            // downloaded it — see AppUpdateChecker.clearCache.
            AppUpdateChecker.clearCache(getApplication())
            AppUpdateChecker.check()
        }
    }

    /**
     * Whether a play has been registered since the home feed was last fetched.
     *
     * The feed leads with listening history, so it's out of date the moment a
     * track starts — but re-fetching there would rearrange the page under
     * whoever is reading it, and the tab is usually in the background anyway.
     * It's re-fetched when the tab is next opened instead.
     */
    private var homeStale = false

    /** Call when the home tab becomes visible. */
    fun onHomeShown() {
        if (!homeStale) return
        homeStale = false
        // A first load already in flight will pick the new play up by itself.
        if (_home.value is UiState.Success) refresh(Feed.HOME)
    }

    /** Historical Google account data stays on disk; it is no longer fetched. */
    fun loadAccount() {
        accountLoadJob?.cancel()
        _account.value = null
    }

    /** Only canonical discovery is a live refresh source in this build. */
    fun refresh(feed: Feed) {
        if (feed in _refreshing.value) return
        _refreshing.value = _refreshing.value + feed
        viewModelScope.launch {
            try {
                when (feed) {
                    Feed.HOME, Feed.EXPLORE -> {
                        loadCanonicalDiscoveryCatalog()
                        loadCanonicalWorldwideReleases(force = true)
                    }
                    Feed.LIBRARY -> loadLibrary()
                }
            } finally {
                _refreshing.value = _refreshing.value - feed
            }
        }
    }

    fun loadExplore() {
        _explore.value = UiState.Success(emptyList())
        loadCanonicalDiscoveryCatalog()
        loadCanonicalWorldwideReleases()
    }

    /** An old YouTube category link has no valid provider-backed destination. */
    fun openMoodGenre(@Suppress("UNUSED_PARAMETER") item: MoodGenre) {
        moodGenreRequestJob?.cancel()
        _selectedMoodGenre.value = null
        _moodGenreShelves.value = UiState.Error(text(R.string.nothing_to_explore))
    }

    fun closeMoodGenre(): Boolean {
        if (_selectedMoodGenre.value == null) return false
        moodGenreRequestJob?.cancel()
        exploreLoadGeneration.invalidateAndNext()
        _selectedMoodGenre.value = null
        return true
    }

    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
    }

    fun loadHome() {
        homeLoadJob?.cancel()
        homePagingJob?.cancel()
        homeLoadGeneration.invalidateAndNext()
        _home.value = UiState.Success(emptyList())
        homeContinuation = null
        homeSeenTitles.clear()
        _homeLoadingMore.value = false
        _homeRecentlyPlayedLoading.value = false
        _homePendingShelves.value = 0
        loadCanonicalDiscoveryCatalog()
        loadCanonicalWorldwideReleases()
    }

    fun loadMoreHome() {
        homeContinuation = null
        _homeLoadingMore.value = false
    }

    /** Legacy account library is historical; the unified library is native. */
    fun loadLibrary() {
        libraryLoadJob?.cancel()
        _library.value = UiState.Error(text(R.string.legacy_library_youtube_disabled))
    }

    /** The dedicated listening store owns recent playback, not YouTube. */
    fun loadHistory() {
        historyLoadJob?.cancel()
        _history.value = UiState.Error(text(R.string.history_empty))
    }

    /** Recent searches, kept on device as rich entity records. */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    /**
     * Records a full entity payload in the history — used when the user taps
     * a suggestion, result row, or typeahead card so the recents list shows
     * real artwork and metadata instead of raw text.
     */
    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    /**
     * Records a track entity from a search hit.
     * Checks _typeaheadResults first (live media while typing), then _results
     * (committed search). Falls back to recording the raw query if no song data
     * is available.
     */
    fun recordSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        // Prefer typeahead results — they are live and always available while typing.
        val fromTypeahead = _typeaheadResults.value.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (fromTypeahead is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        if (fromTypeahead is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        // Fall back to committed search results.
        val topResult = (_results.value as? UiState.Success)?.data?.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (topResult is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else if (topResult is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else {
            // Fallback: record as a raw-text entity for backwards compatibility
            recordEntity(SearchHistoryEntity(
                id = "q:$q",
                title = q,
                subtitle = "",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ))
        }
    }

    /**
     * Runs a term the user picked out of a list rather than typed — a recent
     * search, or one of [suggestions] — and floats it to the top of the
     * history. Picking is as deliberate as submitting, so it searches on the
     * spot.
     *
     * Recording happens downstream via [recordSearch] when the user actually
     * interacts with a result (taps a song, album, or artist card), which
     * ensures the saved entity carries real artwork and metadata instead of
     * a blank placeholder.
     */
    fun searchFor(term: String) {
        _query.value = term
        val allowed = effectiveSearchSource(_searchSource.value, SourceRegistry.configs.value)
        if (allowed != _searchSource.value) switchSearchSource(allowed, rerunCurrentQuery = false)
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        // A saved search/suggestion must stay in its selected namespace.
        // Never send a MusicBrainz query to YouTube or interpret an MBID as a
        // bare videoId. Only actual YouTube searches use anonymous history.
        when (_searchSource.value) {
            SearchSource.YOUTUBE -> {
                runSearch(anonymous = true)
                runAddonSearch(term.trim(), recordHistory = false)
            }
            SearchSource.CANONICAL -> {
                SearchHistory.recordCanonicalQuery(term)
                runCanonicalCatalogSearch(term.trim())
                runAddonSearch(term.trim(), recordHistory = false)
            }
            SearchSource.ADDONS -> runAddonSearch(term.trim())
            SearchSource.LIBRARY -> loadLibrarySongs()
        }
    }

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) {
        val effective = effectiveSearchSource(_searchSource.value, SourceRegistry.configs.value)
        _query.value = newValue
        if (effective != _searchSource.value) {
            switchSearchSource(effective, rerunCurrentQuery = false)
        }
        if (newValue.isBlank()) {
            searchSubmitted = false
            _suggestions.value = emptyList()
            _typeaheadResults.value = emptyList()
            _results.value = null
            addonSearchJob?.cancel()
            _addonResults.value = null
            serverSearchJob?.cancel()
            _serverResults.value = null
            canonicalSearchJob?.cancel()
            canonicalTypeaheadJob?.cancel()
            resetCanonicalSearchPagination()
            _canonicalResults.value = null
            clearCanonicalCatalogNavigation()
            // The list is about to switch from search results to recent
            // searches (or the empty state) — without this it can keep
            // whatever scroll offset the results list was left at, landing
            // the new, much shorter list somewhere other than the top.
            _searchScrollReset.value += 1
            return
        }
        // Reset the submission gate so typeahead pipelines fire again.
        serverSearchJob?.cancel()
        _serverResults.value = null
        addonSearchJob?.cancel()
        _addonResults.value = null
        searchSubmitted = false
        if (_searchSource.value == SearchSource.CANONICAL) {
            // A stale metadata result must not appear under a new query.
            canonicalSearchJob?.cancel()
            canonicalTypeaheadJob?.cancel()
            resetCanonicalSearchPagination()
            _canonicalResults.value = null
            clearCanonicalCatalogNavigation()
            showCachedCanonicalTypeahead(newValue.trim())
        }
        // The Library source answers from the device on every keystroke (see
        // [libraryResults]); YouTube's completions have nothing to add to it.
        if (_searchSource.value != SearchSource.YOUTUBE) return
        // While typing, surface text completions — the pipeline already feeds
        // them through [suggestRequests] and publishes results via typeahead.
        suggestRequests.tryEmit(newValue)
    }

    /**
     * Commits the current query text: clears suggestions/typeahead, runs the
     * full search, and records the term in history for future recall.
     */
    fun submitSearch() {
        val allowed = effectiveSearchSource(_searchSource.value, SourceRegistry.configs.value)
        if (allowed != _searchSource.value) switchSearchSource(allowed, rerunCurrentQuery = false)
        val q = _query.value.trim()
        if (q.isEmpty() || _searchSource.value == SearchSource.LIBRARY) return
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        if (_searchSource.value == SearchSource.ADDONS) {
            runAddonSearch(q)
            return
        }
        if (_searchSource.value == SearchSource.CANONICAL) {
            SearchHistory.recordCanonicalQuery(q)
            runCanonicalCatalogSearch(q)
            runAddonSearch(q, recordHistory = false)
            return
        }
        runSearch(anonymous = false)
        runAddonSearch(q, recordHistory = false)
    }

    /**
     * Fast typeahead from identities already indexed on this phone. Public
     * MusicBrainz has a shared 1 req/s budget: never send a request on each
     * keystroke. Submitting the query performs the explicit live search.
     */
    private fun showCachedCanonicalTypeahead(query: String) {
        canonicalTypeaheadJob?.cancel()
        if (query.isBlank()) return
        canonicalTypeaheadJob = viewModelScope.launch {
            delay(180L)
            if (_searchSource.value != SearchSource.CANONICAL ||
                searchSubmitted || _query.value.trim() != query
            ) return@launch

            val cached = try {
                withContext(Dispatchers.IO) {
                    val dao = CanonicalCatalogDatabase.get(getApplication<Application>()).catalogDao()
                    val recordings = dao.searchKnownMusicBrainzRecordings(query, 8)
                    val available = dao.musicBrainzRecordingMbidsWithAssets(
                        recordings.map { it.musicBrainzId },
                    ).toSet()
                    buildList {
                        dao.searchKnownMusicBrainzArtists(query, 6).forEach { artist ->
                            add(CanonicalCatalogSearchRow(
                                artist.musicBrainzId, CanonicalSearchKind.ARTIST,
                                artist.name, artist.disambiguation ?: artist.country,
                                fromOfflineCache = true,
                            ))
                        }
                        dao.searchKnownMusicBrainzGroups(query, 6).forEach { group ->
                            add(CanonicalCatalogSearchRow(
                                group.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP,
                                group.title, group.artistDisplay ?: group.primaryType,
                                releaseDate = group.firstReleaseDate, releaseKind = group.primaryType,
                                secondaryTypes = group.secondaryTypes,
                                fromOfflineCache = true,
                            ))
                        }
                        dao.searchKnownMusicBrainzReleases(query, 6)
                            .filter(::isCuratedMusicBrainzRelease).forEach { release ->
                            add(CanonicalCatalogSearchRow(
                                release.musicBrainzId, CanonicalSearchKind.RELEASE,
                                release.title,
                                listOfNotNull(release.artistDisplay, release.country, release.releaseDate)
                                    .joinToString(" · "),
                                fromOfflineCache = true,
                            ))
                        }
                        recordings.filter(::isCuratedMusicBrainzRecording).forEach { recording ->
                            add(CanonicalCatalogSearchRow(
                                recording.musicBrainzId, CanonicalSearchKind.RECORDING,
                                recording.title, recording.artistDisplay,
                                hasCatalogedAsset = recording.musicBrainzId in available,
                                fromOfflineCache = true,
                                durationMs = recording.durationMs,
                            ))
                        }
                    }.distinctBy { it.kind to it.musicBrainzId }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { emptyList() }

            if (!searchSubmitted && _searchSource.value == SearchSource.CANONICAL &&
                _query.value.trim() == query && cached.isNotEmpty()
            ) {
                val verified = withVerifiedLocalBadges(cached)
                if (!searchSubmitted && _searchSource.value == SearchSource.CANONICAL &&
                    _query.value.trim() == query
                ) _canonicalResults.value = UiState.Success(verified)
            }
        }
    }

    /**
     * Independent MusicBrainz search. Each page is metadata-only: never map an
     * MBID into Song.videoId (that would route it through YouTube playback).
     * A provider-owned MediaAsset lookup must be wired before enabling play.
     */
    private fun runCanonicalCatalogSearch(query: String) {
        canonicalSearchJob?.cancel()
        canonicalTypeaheadJob?.cancel()
        resetCanonicalSearchPagination()
        clearCanonicalCatalogNavigation()
        _searchScrollReset.value += 1
        _canonicalResults.value = UiState.Loading
        canonicalSearchJob = viewModelScope.launch {
            // Resolve the artist identity FIRST. A query exactly naming an
            // artist must browse that artist's MBID, not return unrelated
            // recordings with the same word in their title.
            val artists = musicBrainzSearch.searchArtists(query, limit = 15)
            val artistScopeMbid = if (artists is MusicBrainzCatalogSearchResult.Success) {
                artists.page.items.firstOrNull {
                    it.name.equals(query.trim(), ignoreCase = true)
                }?.musicBrainzId
            } else null
            val recordings = if (artistScopeMbid != null) {
                musicBrainzSearch.searchRecordingsForArtistMbid(artistScopeMbid, limit = 25)
            } else musicBrainzSearch.searchRecordings(query, limit = 25)
            val groups = if (artistScopeMbid != null) {
                musicBrainzSearch.searchReleaseGroupsForArtistMbid(artistScopeMbid, limit = 15)
            } else musicBrainzSearch.searchReleaseGroups(query, limit = 15)
            val editions = if (artistScopeMbid != null) {
                musicBrainzSearch.searchReleasesForArtistMbid(artistScopeMbid, limit = 15)
            } else musicBrainzSearch.searchReleases(query, limit = 15)
            if (_searchSource.value != SearchSource.CANONICAL || _query.value.trim() != query) {
                return@launch
            }
            if (groups is MusicBrainzCatalogSearchResult.Success) {
                try {
                    withContext(Dispatchers.IO) {
                        MusicBrainzCatalogIndex(
                            CanonicalCatalogDatabase.get(getApplication<Application>()),
                        ).persistReleaseGroups(groups.page.items)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Offline index is optional, never an audio stream. */ }
            }
            if (artists is MusicBrainzCatalogSearchResult.Success) {
                try {
                    withContext(Dispatchers.IO) {
                        MusicBrainzCatalogIndex(
                            CanonicalCatalogDatabase.get(getApplication<Application>()),
                        ).persistArtists(artists.page.items)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Network results remain independent. */ }
            }
            if (editions is MusicBrainzCatalogSearchResult.Success) {
                try {
                    withContext(Dispatchers.IO) {
                        MusicBrainzCatalogIndex(
                            CanonicalCatalogDatabase.get(getApplication<Application>()),
                        ).persistReleases(editions.page.items)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Never mistake edition metadata for available audio. */ }
            }
            // A public Recording remains known even with zero MediaAssets.
            // Only a trusted MBID claim creates/reuses a canonical identity;
            // never match local tracks by title or artist name.
            val catalogedAssetMbids = if (recordings is MusicBrainzCatalogSearchResult.Success) {
                try {
                    withContext(Dispatchers.IO) {
                        val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                        MusicBrainzCatalogIndex(db).persistRecordings(recordings.page.items)
                        db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                            recordings.page.items.map { it.musicBrainzId },
                        ).toSet()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptySet()
                }
            } else emptySet()
            val rows = buildList {
                if (recordings is MusicBrainzCatalogSearchResult.Success) {
                    recordings.page.items.filter(::isCuratedMusicBrainzRecording).forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            musicBrainzId = hit.musicBrainzId,
                            kind = CanonicalSearchKind.RECORDING,
                            title = hit.title,
                            subtitle = hit.artistDisplay,
                            hasCatalogedAsset = hit.musicBrainzId in catalogedAssetMbids,
                            artistScopeMbid = artistScopeMbid,
                            coverReleaseMbid = hit.coverReleaseMbid,
                            coverGroupMbid = hit.coverGroupMbid,
                            durationMs = hit.durationMs,
                        ))
                    }
                }
                if (groups is MusicBrainzCatalogSearchResult.Success) {
                    groups.page.items.forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            musicBrainzId = hit.musicBrainzId,
                            kind = CanonicalSearchKind.RELEASE_GROUP,
                            title = hit.title,
                            subtitle = hit.artistDisplay ?: hit.primaryType,
                            releaseDate = hit.firstReleaseDate,
                            releaseKind = hit.primaryType,
                            secondaryTypes = hit.secondaryTypes,
                            artistScopeMbid = artistScopeMbid,
                        ))
                    }
                }
                if (editions is MusicBrainzCatalogSearchResult.Success) {
                    editions.page.items.filter(::isCuratedMusicBrainzRelease).forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            musicBrainzId = hit.musicBrainzId,
                            kind = CanonicalSearchKind.RELEASE,
                            title = hit.title,
                            subtitle = listOfNotNull(hit.artistDisplay, hit.country, hit.releaseDate)
                                .joinToString(" · "),
                            releaseDate = hit.releaseDate,
                            artistScopeMbid = artistScopeMbid,
                            coverGroupMbid = hit.releaseGroupMbid,
                        ))
                    }
                }
                if (artists is MusicBrainzCatalogSearchResult.Success) {
                    artists.page.items.forEach { hit ->
                        add(CanonicalCatalogSearchRow(
                            musicBrainzId = hit.musicBrainzId,
                            kind = CanonicalSearchKind.ARTIST,
                            title = hit.name,
                            subtitle = hit.disambiguation ?: hit.country,
                        ))
                    }
                }
            }.distinctBy { it.kind to it.musicBrainzId }
            val pageCursors = CanonicalSearchPageCursors.initial(
                query, recordings, groups, editions, artists,
                artistScopeMbid = artistScopeMbid,
            )
            canonicalSearchPageCursors = pageCursors
            _canonicalSearchHasMore.value = pageCursors.hasMore
            val failedKinds = buildSet<CanonicalSearchKind> {
                if (recordings is MusicBrainzCatalogSearchResult.Failure) add(CanonicalSearchKind.RECORDING)
                if (groups is MusicBrainzCatalogSearchResult.Failure) add(CanonicalSearchKind.RELEASE_GROUP)
                if (editions is MusicBrainzCatalogSearchResult.Failure) add(CanonicalSearchKind.RELEASE)
                if (artists is MusicBrainzCatalogSearchResult.Failure) add(CanonicalSearchKind.ARTIST)
            }
            // Recover each failed type independently. Successful MusicBrainz
            // responses take precedence and must not be replaced by stale cache.
            val cached: List<CanonicalCatalogSearchRow> = if (failedKinds.isEmpty()) {
                emptyList()
            } else try {
                withContext(Dispatchers.IO) {
                    val dao = CanonicalCatalogDatabase.get(getApplication<Application>()).catalogDao()
                    buildList {
                        if (CanonicalSearchKind.RECORDING in failedKinds) {
                            val known = dao.searchKnownMusicBrainzRecordings(query, 25)
                            val available = dao.musicBrainzRecordingMbidsWithAssets(
                                known.map { it.musicBrainzId },
                            ).toSet()
                            known.filter(::isCuratedMusicBrainzRecording).forEach { hit -> add(CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.RECORDING,
                                hit.title, hit.artistDisplay,
                                hasCatalogedAsset = hit.musicBrainzId in available,
                                fromOfflineCache = true,
                                durationMs = hit.durationMs,
                            )) }
                        }
                        if (CanonicalSearchKind.RELEASE_GROUP in failedKinds) {
                            dao.searchKnownMusicBrainzGroups(query, 25).forEach { hit ->
                                add(CanonicalCatalogSearchRow(
                                    hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP, hit.title,
                                    hit.artistDisplay ?: hit.primaryType, fromOfflineCache = true,
                                    releaseDate = hit.firstReleaseDate,
                                    releaseKind = hit.primaryType, secondaryTypes = hit.secondaryTypes,
                                ))
                            }
                        }
                        if (CanonicalSearchKind.RELEASE in failedKinds) {
                            dao.searchKnownMusicBrainzReleases(query, 25)
                                .filter(::isCuratedMusicBrainzRelease).forEach { hit ->
                                add(CanonicalCatalogSearchRow(
                                    hit.musicBrainzId, CanonicalSearchKind.RELEASE, hit.title,
                                    listOfNotNull(hit.artistDisplay, hit.country, hit.releaseDate)
                                        .joinToString(" · "), fromOfflineCache = true,
                                ))
                            }
                        }
                        if (CanonicalSearchKind.ARTIST in failedKinds) {
                            dao.searchKnownMusicBrainzArtists(query, 25).forEach { hit ->
                                add(CanonicalCatalogSearchRow(
                                    hit.musicBrainzId, CanonicalSearchKind.ARTIST, hit.name,
                                    hit.disambiguation ?: hit.country, fromOfflineCache = true,
                                ))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (_searchSource.value != SearchSource.CANONICAL || _query.value.trim() != query) {
                return@launch
            }
            val combined = withVerifiedLocalBadges(
                mergeCanonicalSearchRows(rows, cached, failedKinds).filter { row ->
                    row.kind != CanonicalSearchKind.RECORDING || canonicalStreamingTitleIsSafe(row.title)
                },
            )
            if (_searchSource.value != SearchSource.CANONICAL ||
                _query.value.trim() != query
            ) return@launch
            _canonicalResults.value =
                if (failedKinds.size == CanonicalSearchKind.entries.size && combined.isEmpty()) {
                    UiState.Error("MusicBrainz unavailable and no local matches")
                } else UiState.Success(combined)
            if (_canonicalResults.value is UiState.Success) enrichCanonicalSearchCovers(query)
        }
    }


    /** Each entity type continues independently; failures retain its raw offset for retry. */
    fun loadMoreCanonicalSearchResults() {
        val cursor = canonicalSearchPageCursors ?: return
        if (_searchSource.value != SearchSource.CANONICAL ||
            _query.value.trim() != cursor.query ||
            _canonicalDetail.value != null ||
            _canonicalSearchLoadingMore.value ||
            !cursor.hasMore ||
            _canonicalResults.value !is UiState.Success
        ) return
        _canonicalSearchLoadingMore.value = true
        canonicalSearchPagingJob?.cancel()
        canonicalSearchPagingJob = viewModelScope.launch {
            try {
                val recordings = cursor.recordings?.let { offset ->
                    cursor.artistScopeMbid?.let {
                        musicBrainzSearch.searchRecordingsForArtistMbid(it, limit = 25, offset = offset)
                    } ?: musicBrainzSearch.searchRecordings(cursor.query, limit = 25, offset = offset)
                }
                val groups = cursor.groups?.let { offset ->
                    cursor.artistScopeMbid?.let {
                        musicBrainzSearch.searchReleaseGroupsForArtistMbid(it, limit = 15, offset = offset)
                    } ?: musicBrainzSearch.searchReleaseGroups(cursor.query, limit = 15, offset = offset)
                }
                val editions = cursor.releases?.let { offset ->
                    cursor.artistScopeMbid?.let {
                        musicBrainzSearch.searchReleasesForArtistMbid(it, limit = 15, offset = offset)
                    } ?: musicBrainzSearch.searchReleases(cursor.query, limit = 15, offset = offset)
                }
                val artists = cursor.artists?.let {
                    musicBrainzSearch.searchArtists(cursor.query, limit = 15, offset = it)
                }
                if (_searchSource.value != SearchSource.CANONICAL ||
                    _query.value.trim() != cursor.query ||
                    canonicalSearchPageCursors !== cursor || _canonicalDetail.value != null
                ) return@launch

                val assetMbids = try {
                    withContext(Dispatchers.IO) {
                        val db = CanonicalCatalogDatabase.get(getApplication<Application>())
                        val index = MusicBrainzCatalogIndex(db)
                        if (groups is MusicBrainzCatalogSearchResult.Success) {
                            index.persistReleaseGroups(groups.page.items)
                        }
                        if (editions is MusicBrainzCatalogSearchResult.Success) {
                            index.persistReleases(editions.page.items)
                        }
                        if (artists is MusicBrainzCatalogSearchResult.Success) {
                            index.persistArtists(artists.page.items)
                        }
                        if (recordings is MusicBrainzCatalogSearchResult.Success) {
                            index.persistRecordings(recordings.page.items)
                            db.catalogDao().musicBrainzRecordingMbidsWithAssets(
                                recordings.page.items.map { it.musicBrainzId },
                            ).toSet()
                        } else emptySet()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptySet()
                }
                if (_searchSource.value != SearchSource.CANONICAL ||
                    _query.value.trim() != cursor.query || canonicalSearchPageCursors !== cursor ||
                    _canonicalDetail.value != null
                ) return@launch
                val additional = buildList {
                    if (recordings is MusicBrainzCatalogSearchResult.Success) {
                        recordings.page.items.filter(::isCuratedMusicBrainzRecording).forEach { hit ->
                            add(CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.RECORDING, hit.title,
                                hit.artistDisplay, hasCatalogedAsset = hit.musicBrainzId in assetMbids,
                                artistScopeMbid = cursor.artistScopeMbid,
                                coverReleaseMbid = hit.coverReleaseMbid,
                            coverGroupMbid = hit.coverGroupMbid,
                                durationMs = hit.durationMs,
                            ))
                        }
                    }
                    if (groups is MusicBrainzCatalogSearchResult.Success) {
                        groups.page.items.forEach { hit ->
                            add(CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.RELEASE_GROUP, hit.title,
                                hit.artistDisplay ?: hit.primaryType,
                                releaseDate = hit.firstReleaseDate, releaseKind = hit.primaryType,
                                secondaryTypes = hit.secondaryTypes,
                                artistScopeMbid = cursor.artistScopeMbid,
                            ))
                        }
                    }
                    if (editions is MusicBrainzCatalogSearchResult.Success) {
                        editions.page.items.filter(::isCuratedMusicBrainzRelease).forEach { hit ->
                            add(CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.RELEASE, hit.title,
                                listOfNotNull(hit.artistDisplay, hit.country, hit.releaseDate)
                                    .joinToString(" · "),
                                artistScopeMbid = cursor.artistScopeMbid,
                                coverGroupMbid = hit.releaseGroupMbid,
                            ))
                        }
                    }
                    if (artists is MusicBrainzCatalogSearchResult.Success) {
                        artists.page.items.forEach { hit ->
                            add(CanonicalCatalogSearchRow(
                                hit.musicBrainzId, CanonicalSearchKind.ARTIST, hit.name,
                                hit.disambiguation ?: hit.country,
                            ))
                        }
                    }
                }
                val existing = (_canonicalResults.value as? UiState.Success)?.data ?: return@launch
                val verified = withVerifiedLocalBadges(
                    (existing + additional).distinctBy { it.kind to it.musicBrainzId },
                )
                if (_searchSource.value != SearchSource.CANONICAL ||
                    _query.value.trim() != cursor.query ||
                    canonicalSearchPageCursors !== cursor || _canonicalDetail.value != null
                ) return@launch
                _canonicalResults.value = UiState.Success(verified)
                enrichCanonicalSearchCovers(cursor.query)
                val advanced = cursor.advance(recordings, groups, editions, artists)
                canonicalSearchPageCursors = advanced
                _canonicalSearchHasMore.value = advanced.hasMore
            } finally {
                if (canonicalSearchPageCursors === cursor ||
                    canonicalSearchPageCursors?.query == cursor.query
                ) _canonicalSearchLoadingMore.value = false
            }
        }
    }

    /**
     * Asks every enabled addon for [q] in parallel. A slow or failing addon
     * only costs its own rows; the list is the rest of them, round-robin so
     * no single catalogue buries the others.
     */
    private fun runAddonSearch(q: String, recordHistory: Boolean = true) {
        if (recordHistory) SearchHistory.recordAddonQuery(q)
        runServerAssetSearch(q)
        addonSearchJob?.cancel()
        if (recordHistory) _searchScrollReset.value += 1
        val addons = SourceRegistry.active().filterIsInstance<AddonSource>()
        if (addons.isEmpty()) {
            _addonResults.value = UiState.Success(emptyList())
            return
        }
        _addonResults.value = UiState.Loading
        addonSearchJob = viewModelScope.launch {
            val perAddon = coroutineScope {
                addons.map { addon ->
                    async {
                        withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                            runCatching { addon.search(q, limit = ADDON_SEARCH_LIMIT) }.getOrDefault(emptyList())
                        } ?: emptyList()
                    }
                }.map { it.await() }
            }
            val merged = buildList {
                val longest = perAddon.maxOfOrNull { it.size } ?: 0
                for (i in 0 until longest) perAddon.forEach { rows -> rows.getOrNull(i)?.let(::add) }
            }
            _addonResults.value = UiState.Success(merged)
        }
    }

    /** Read-only persisted remote search. Playback revalidates account access. */
    private fun runServerAssetSearch(query: String) {
        serverSearchJob?.cancel()
        _serverResults.value = UiState.Loading
        serverSearchJob = viewModelScope.launch {
            val result: UiState<List<CanonicalServerSearchRow>> = try {
                val rows = withContext(Dispatchers.IO) {
                    CanonicalCatalogDatabase.get(getApplication<Application>())
                        .catalogDao().searchCanonicalRemoteServerAssets(query, limit = 250)
                }
                UiState.Success(rows.distinctBy { it.recordingId to it.providerKey }.map { row ->
                    CanonicalServerSearchRow(
                        recordingId = row.recordingId,
                        mediaAssetId = row.mediaAssetId,
                        providerKey = row.providerKey,
                        title = row.title,
                        artistDisplay = row.artistDisplay,
                        artworkUri = row.artworkUri,
                    )
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UiState.Error(text(R.string.canonical_discovery_cache_error))
            }
            if (_query.value.trim() == query &&
                _searchSource.value in setOf(
                    SearchSource.CANONICAL, SearchSource.YOUTUBE, SearchSource.ADDONS,
                )
            ) _serverResults.value = result
        }
    }

    /**
     * Points the Search tab at YouTube Music or at the Local Music folder.
     *
     * The query carries over: whatever is in the field is answered again by
     * the source just picked, so flipping is a way to compare the two rather
     * than a reset.
     */
    fun setSearchSource(source: SearchSource) {
        val allowed = effectiveSearchSource(source, SourceRegistry.configs.value)
        searchSourceExplicitlySelected = true
        switchSearchSource(allowed)
    }

    /**
     * Reads the Local Music folder for the Library source. Re-read on every
     * switch to it, so files added since — or a permission granted since —
     * are in the next answer; the last list stays up meanwhile.
     */
    fun loadLibrarySongs() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _librarySongs.value = if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                runCatching { LocalMediaRepository.getLocalMusic(context) }.fold(
                    onSuccess = { songs ->
                        if (songs.isEmpty()) UiState.Error(text(R.string.no_local_audio_found))
                        else UiState.Success(songs)
                    },
                    onFailure = { UiState.Error(text(R.string.no_local_audio_found)) },
                )
            }
        }
    }

    fun onFilterChange(value: SearchFilter) {
        val source = _searchSource.value
        if (source != SearchSource.YOUTUBE && source != SearchSource.CANONICAL) return
        if (_filter.value == value) return
        _filter.value = value
        if (source == SearchSource.CANONICAL) {
            // All four entity types are already queried with independent MB
            // cursors. Changing presentation must not spend another public
            // API request or accidentally reinterpret an MBID as videoId.
            _searchScrollReset.value += 1
            return
        }
        searchSubmitted = true
        runSearch()
    }

    /**
     * A search asked for, as a request the pipeline below decides what to do
     * with.
     *
     * [requestId] is what makes a late answer harmless: a response is only
     * written to the screen if its id is still the newest one asked for.
     */
    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
        val anonymous: Boolean = true,
    )

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    /**
     * The results of the longest earlier query this one starts with — near
     * enough to leave up while the narrower search runs.
     */
    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return searchCache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
            ?.rows
    }

    private fun runSearch(anonymous: Boolean = true) {
        if (_searchSource.value != SearchSource.YOUTUBE ||
            effectiveSearchSource(SearchSource.YOUTUBE, SourceRegistry.configs.value) != SearchSource.YOUTUBE
        ) return // No hidden YT search after a provider opt-out.
        val query = _query.value
        if (query.isBlank()) {
            // Nothing in flight can still be waiting to overwrite this: the
            // id it would be checked against has already moved past it.
            newestRequestId.incrementAndGet()
            searchSession = null
            _searchLoadingMore.value = false
            _results.value = null
            return
        }
        val id = newestRequestId.incrementAndGet()
        _searchScrollReset.value += 1
        searchSession = null
        _searchLoadingMore.value = false
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id, anonymous))
    }

    /**
     * Legacy YouTube search events are accepted for restored navigation, but
     * never routed to a retired catalog. Canonical search has its own typed
     * MusicBrainz/MediaStore/OpenSubsonic presentation pipeline.
     */
    private fun startSearchPipeline() = viewModelScope.launch {
        searchRequests.collectLatest { request ->
            searchSession = null
            _searchLoadingMore.value = false
            _results.value = if (request.query.isBlank()) null
                else UiState.Error(text(R.string.legacy_library_youtube_disabled))
        }
    }

    fun loadMoreSearchResults() {
        searchSession = null
        _searchLoadingMore.value = false
    }

    private fun startSuggestPipeline() = viewModelScope.launch {
        suggestRequests.collectLatest { input ->
            _suggestions.value = if (input.isNotBlank() &&
                _searchSource.value == SearchSource.YOUTUBE && !searchSubmitted
            ) listOf(input) else emptyList()
        }
    }

    private fun startTypeaheadMediaPipeline() = viewModelScope.launch {
        suggestRequests.collectLatest {
            _typeaheadResults.value = emptyList()
        }
    }

    /**
     * The enabled non-YouTube sources, asked at the same time and returned
     * split at YouTube's own place in the order.
     *
     * The split is what makes the Sources screen's ordering visible where it
     * matters most. A library server ranked above YouTube puts its own copies
     * at the top of the results — which is the whole point of ranking it there —
     * and one ranked below appears under them instead.
     *
     * Only the Songs filter fans out: albums, artists and playlists are
     * browse-shaped, and [MusicSource] deliberately answers for tracks only.
     *
     * Asked of the playback list rather than every enabled source, so a result
     * offered here is one this connection's ceiling would actually let play —
     * a row that can only be tapped into a YouTube stream is a lie about where
     * the track is coming from.
     */
    private suspend fun sourceResults(
        query: String,
        filter: SearchFilter,
    ): Pair<List<SearchResult>, List<SearchResult>> = coroutineScope {
        if (filter != SearchFilter.SONGS) return@coroutineScope emptyList<SearchResult>() to emptyList()
        val active = SourceRegistry.activeForPlayback()
        val youtubeRank = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
            .let { if (it < 0) active.size else it }

        val answers = active
            .filter { it.kind != SourceKind.YOUTUBE }
            .map { source ->
                source to async {
                    // Per-source, so one slow or unreachable server delays the
                    // results by at most this much rather than for as long as
                    // its socket takes to give up.
                    runCatching {
                        withTimeout(SOURCE_SEARCH_TIMEOUT_MS) { source.search(query, SOURCE_SEARCH_LIMIT) }
                    }.getOrDefault(emptyList())
                }
            }

        val above = mutableListOf<SearchResult>()
        val below = mutableListOf<SearchResult>()
        answers.forEach { (source, job) ->
            val rows = job.await().map { SearchResult.Track(it) }
            val rank = active.indexOfFirst { it.configId == source.configId }
            if (rank in 0 until youtubeRank) above += rows else below += rows
        }
        above to below
    }

    companion object {
        /** Per-addon budget for the Addons search tab; a slow addon is left out, not waited on. */
        private const val ADDON_SEARCH_TIMEOUT_MS = 12_000L
        private const val ADDON_SEARCH_LIMIT = 20

        /** How many Spotify tracks are looked up on YouTube Music at once. */
        private const val SPOTIFY_MATCH_PARALLELISM = 6

        /**
         * How long a keystroke waits before the typeahead is asked about it.
         *
         * Not the search's timer — searches aren't on a timer any more. This
         * one only stops a fast typist spending a round trip per letter, so it
         * wants to be as short as it can be while still collapsing a burst:
         * long enough that "cold" isn't four lookups, short enough that the
         * list is up by the time the thumb has left the key.
         */
        const val SUGGEST_DEBOUNCE_MS = 180L

        /**
         * Debounce for the parallel media-search pipeline. Slightly longer than
         * text suggestions so it doesn't fire on every single keystroke — a
         * full search is heavier than a suggestion request, and the UI only
         * needs a few results to fill the dropdown.
         */
        const val TYPEAHEAD_MEDIA_DEBOUNCE_MS = 350L

        /**
         * Maximum number of live media results shown in the typeahead dropdown.
         * Enough to give variety without making the list unscrollable.
         */
        const val TYPEAHEAD_MAX_RESULTS = 15

        const val SEARCH_CACHE_ENTRIES = 100

        /**
         * How long any one source gets to answer a search.
         *
         * Short on purpose: these run alongside the YouTube search, and their
         * only job is to be *there* when it lands. A home server reached over
         * a VPN that takes eight seconds has effectively not answered, and
         * holding the whole result list for it would make search feel worse
         * for the sake of results the user can still get by searching again.
         */
        const val SOURCE_SEARCH_TIMEOUT_MS = 4000L

        /** Enough to be worth scrolling, short enough not to bury YouTube's own rows. */
        const val SOURCE_SEARCH_LIMIT = 12

        /**
         * What a page with an empty listing says.
         *
         * Named because it is a state one can be got *out* of, not just a
         * message: an own playlist with nothing in it lands here, and adding the
         * first track to it has to be able to tell "this page is empty" apart
         * from "this page failed to load" — see [appendToOpenPlaylist].
         */

        /**
         * What a downloaded playlist's page says once the files under it are
         * gone.
         *
         * A record here outlives the folder it names — the user is expected to
         * manage Downloads with a file manager — so this is a state its page has
         * to be able to reach, not an error. Named because three places say it:
         * the page, its refresh, and the long-press menu that queues it without
         * opening it.
         */

        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            // Built for playlists, but an album downloaded whole opens through
            // the same prefix, and its page should read as the album it is.
            browseId.startsWith(Downloads.PLAYLIST_PREFIX) ->
                Downloads.recordIdOf(browseId)
                    ?.let { Downloads.collections.value[it] }
                    ?.takeIf { !it.playlist }
                    ?.let { BrowseType.ALBUM }
                    ?: BrowseType.PLAYLIST
            browseId.startsWith(LocalGroups.PREFIX) -> LocalGroups.typeOf(browseId) ?: fallback
            browseId.startsWith("UC") -> BrowseType.ARTIST
            browseId.startsWith("MPREb") || browseId.startsWith("VLOLAK") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    /**
     * A remote file library behind a `local:` page: how to list it and what
     * an empty listing says. One entry per library keeps the detail loader,
     * the refresher and the queue collector from each repeating the switch —
     * a third library adds one line here and nothing anywhere else.
     */
    private data class RemoteLibrary(
        val emptyRes: Int,
        val songs: suspend () -> List<Song>,
    )

    private fun remoteLibrary(browseId: String): RemoteLibrary? = when (browseId) {
        com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.webdav_empty,
                com.music.bitchord.data.webdav.WebDavRepository::getSongs,
            )
        com.music.bitchord.data.smb.SmbConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.smb_empty,
                com.music.bitchord.data.smb.SmbRepository::getSongs,
            )
        else -> null
    }

    private suspend fun remoteSongsState(remote: RemoteLibrary): UiState<List<Song>> =
        com.music.bitchord.data.remote.RemoteListing.state(runCatching { remote.songs() }, text(remote.emptyRes))

    /**
     * Re-reads an open remote-library page after its server settings change.
     * A no-op when the page isn't open — the next visit lists fresh anyway.
     */
    private fun reloadRemoteDetail(browseId: String) {
        if (_detailStack.value.any { page -> page.browseId == browseId }) {
            reloadLocalDetail(browseId)
        }
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) {
        // A fast double tap used to push two identical loading pages and launch
        // two identical browse requests. Besides wasting the connection, both
        // completions then raced to update every matching browse id in the
        // stack. The page is pushed synchronously, so this closes that window
        // without suppressing a deliberate revisit after the first page loads.
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) {
            openSpotifyPage(browseId, title, subtitle, thumbnailUrl)
            return
        }
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            var sections = emptyList<HomeShelf>()
            // Callers that open an artist from a track — the player, the
            // long-press menu — only have that track's cover art and its full
            // credit ("A, B & C") to hand, so the page swaps in the artist's
            // own picture and name once they arrive.
            var artwork: String? = null
            var name: String? = null
            /**
             * The credit line, when the page had to supply its own.
             *
             * Only a link tapped outside the app arrives with neither — see
             * [com.music.bitchord.playback.MusicLink]. Every other caller was
             * looking at a card that already said this.
             */
            var credit: String? = null
            /** Set when the track list carries on past its first response. */
            var more: String? = null
            /** Tracks YouTube offers to round the playlist out — see [DetailPage.suggestedSongs]. */
            var suggested: List<Song> = emptyList()
            /** Whether this release is already saved — see [DetailPage.library]. */
            var library: LibraryState? = null
            /** YouTube's own "About" blurb — see [DetailPage.description]. */
            var description: String? = null
            /** Artist header stats — see [DetailPage.subscriberCountText]. */
            var subscriberCountText: String? = null
            var monthlyListenerCount: String? = null
            /** Whether this artist is subscribed to — see [DetailPage.subscription]. */
            var subscription: SubscriptionState? = null
            val localPlaylist = com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(browseId)
            val remote = remoteLibrary(browseId)
            // A release downloaded whole and opened by its YouTube id — the
            // Playlists shelf, a search hit — used to wait on the network for
            // tracks already on the device, and showed an error with no
            // connection at all. Its downloaded copy goes up first; the
            // online listing replaces it if and when that arrives.
            val onDevice = if (localPlaylist == null && remote == null && !browseId.startsWith("local:")) {
                downloadedCopyOf(browseId)
            } else {
                emptyList()
            }
            if (onDevice.isNotEmpty()) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId && it.songs is UiState.Loading) it.copy(songs = UiState.Success(onDevice)) else it
                }
            }
            if (localPlaylist != null) {
                name = localPlaylist.title
                credit = getApplication<Application>().getString(R.string.local_playlist_subtitle, localPlaylist.songs.size)
                artwork = localPlaylist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            }
            val local = localPageState(browseId)
            // Library/Spotify/provider-owned pages are resolved above. Historical
            // YouTube browse IDs remain readable, but never initiate a network lookup.
            val safeState = local ?: UiState.Error(text(R.string.legacy_library_youtube_disabled))
            // Update by id — the user may have pushed another page meanwhile.
            // A page showing its downloaded copy takes only a real listing: a
            // failed or empty fetch leaves the downloaded tracks up.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId &&
                    (it.songs is UiState.Loading || (onDevice.isNotEmpty() && safeState is UiState.Success))
                ) {
                    it.copy(
                        songs = safeState,
                        sections = if (safeState is UiState.Error) emptyList() else sections,
                        thumbnailUrl = artwork ?: it.thumbnailUrl,
                        title = name ?: it.title,
                        subtitle = credit ?: it.subtitle,
                        suggestedSongs = suggested,
                        continuation = more,
                        library = library,
                        description = description,
                        subscriberCountText = subscriberCountText,
                        monthlyListenerCount = monthlyListenerCount,
                        subscription = subscription,
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * A Spotify playlist opened as an ordinary playlist page.
     *
     * Spotify's rows go up as soon as they are read, each marked as still
     * waiting on a verified MediaStore/Navidrome asset (see [isMatchPending]).
     * Resolved assets replace rows in place; unmatched entries retain Spotify
     * metadata, never a guessed YouTube video or canonical Recording identity.
     */
    private fun openSpotifyPage(browseId: String, title: String, subtitle: String, thumbnailUrl: String?) {
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = BrowseType.PLAYLIST,
        )
        viewModelScope.launch {
            val playlistId = browseId.removePrefix(SPOTIFY_PAGE_PREFIX)
            fun open() = _detailStack.value.any { it.browseId == browseId }
            fun setSongs(songs: UiState<List<Song>>) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(songs = songs) else it
                }
            }
            // The list only had a thumbnail; the full-size cover replaces it
            // once this page has asked for it.
            launch {
                val cover = runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull() ?: return@launch
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(thumbnailUrl = cover) else it
                }
            }
            val tracks = runCatching {
                SpotifyLibrary.tracks(playlistId) { soFar ->
                    setSongs(UiState.Success(soFar.map { it.asPendingSong() }))
                }
            }.getOrElse {
                setSongs(UiState.Error(it.message ?: text(R.string.failed)))
                return@launch
            }
            if (tracks.isEmpty()) {
                setSongs(UiState.Error(text(R.string.spotify_empty_tracks)))
                return@launch
            }
            // Resolve only from the user's canonical MediaStore/server assets.
            // A Spotify track ID is never a RecordingId and never falls back
            // to the first YouTube result. Ambiguity remains metadata-only.
            val gate = Semaphore(SPOTIFY_MATCH_PARALLELISM)
            coroutineScope {
                tracks.forEachIndexed { index, track ->
                    launch {
                        gate.withPermit {
                            if (!open()) return@withPermit
                            val match = try {
                                withContext(Dispatchers.IO) {
                                    val candidates = CanonicalCatalogDatabase
                                        .get(getApplication<Application>())
                                        .catalogDao()
                                        .spotifyCanonicalAssetCandidates(track.title)
                                    selectSpotifyCanonicalAsset(track, candidates)?.let { asset ->
                                        spotifyCanonicalAssetAsSong(track, asset)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // A stale/disconnected provider never makes a
                                // Spotify playlist fail or creates fake audio.
                                null
                            }
                            val found = match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.imageUrl)
                                ?: track.asPendingSong().let {
                                    it.copy(videoId = SPOTIFY_MISSING_PREFIX + track.id)
                                }
                            _detailStack.value = _detailStack.value.map { page ->
                                val list = (page.songs as? UiState.Success<List<Song>>)?.data
                                if (page.browseId == browseId && list != null && index < list.size) {
                                    page.copy(songs = UiState.Success(list.toMutableList().also { it[index] = found }))
                                } else page
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SpotifyTrack.asPendingSong() = Song(
        videoId = SPOTIFY_PENDING_PREFIX + id,
        title = title,
        artist = artist,
        thumbnailUrl = imageUrl,
        durationText = durationMs.takeIf { it > 0 }?.let { ms ->
            "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        },
        albumName = album,
    )

    /**
     * Pushes one album, playlist or artist out of a device folder as a page of
     * its own, with the rows the folder already had in hand — so it opens on
     * its tracks rather than on a skeleton while the folder is read again.
     * [reloadLocalDetail] keeps it current from there, like any `local:` page.
     */
    fun openLocalGroup(group: LocalGroup) {
        if (_detailStack.value.lastOrNull()?.browseId == group.browseId) return
        _detailStack.value += DetailPage(
            browseId = group.browseId,
            title = group.title,
            subtitle = group.subtitle,
            thumbnailUrl = group.thumbnailUrl,
            songs = if (group.songs.isEmpty()) UiState.Error(text(R.string.no_tracks_here)) else UiState.Success(group.songs),
            type = group.type,
            sections = if (LocalGroups.isArtist(group.browseId)) {
                LocalGroups.artistReleaseShelves(group.browseId, group.songs)
            } else emptyList(),
        )
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val state = localPageState(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(
                        songs = state,
                        sections = if (LocalGroups.isArtist(browseId) &&
                            state is UiState.Success
                        ) LocalGroups.artistReleaseShelves(browseId, state.data)
                        else it.sections,
                    )
                } else it
            }
        }
    }

    /**
     * The listing behind a page whose tracks come from this device or a file
     * server rather than YouTube — a device folder, a release downloaded whole,
     * a playlist in the app's own store, a remote library, or one album or
     * artist grouped out of a folder — or null for every other page.
     *
     * The one place each of those is read, so opening a page, refreshing it
     * and queueing it from a menu can't disagree about what is on it.
     */
    private suspend fun localPageState(browseId: String): UiState<List<Song>>? {
        val context = getApplication<Application>()
        fun nonEmpty(songs: List<Song>, empty: () -> String): UiState<List<Song>> =
            if (songs.isEmpty()) UiState.Error(empty()) else UiState.Success(songs)

        com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
            return nonEmpty(playlist.songs) { text(R.string.spotify_import_empty_playlist) }
        }
        remoteLibrary(browseId)?.let { return remoteSongsState(it) }
        LocalGroups.folderOf(browseId)?.let { folderId ->
            // Read off a fresh listing of the folder it came from: a grouping
            // has nothing of its own to read, and whatever the folder says
            // when it can't be listed is what this page says too.
            return when (val folder = localPageState(folderId)) {
                is UiState.Success -> nonEmpty(LocalGroups.songsOf(browseId, folder.data)) { text(R.string.no_tracks_here) }
                null -> UiState.Error(text(R.string.no_tracks_here))
                else -> folder
            }
        }
        return when {
            Downloads.recordIdOf(browseId) != null ->
                nonEmpty(downloadedPlaylist(browseId)) { text(R.string.downloaded_playlist_empty) }
            browseId == "local:downloads" ->
                nonEmpty(Downloads.getDownloadedSongs(context)) { "No downloaded tracks" }
            browseId == CACHE_FOLDER_BROWSE_ID -> cachedSongsState()
            browseId == "local:all" -> if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                nonEmpty(LocalMediaRepository.getLocalMusic(context)) { text(R.string.no_local_audio_found) }
            }
            else -> null
        }
    }

    /**
     * The tracks of the downloaded playlist [browseId] names that are still on
     * disk, in the order the playlist had.
     *
     * The download record already names those files, so opening this page must
     * not scan every unrelated download first. [Downloads.getCollectionSongs]
     * verifies only this collection and still collapses aliases that point to
     * the same saved file.
     *
     * Empty is the honest answer for a record whose files have all been deleted
     * from under it, and callers turn that into an empty-state message rather than
     * into a blank page.
     */
    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * The downloaded copy of the release YouTube calls [browseId], if there
     * is one. A playlist is filed under whichever id it was downloaded from,
     * which may or may not carry the `VL` its page id does.
     */
    private suspend fun downloadedCopyOf(browseId: String): List<Song> {
        val bare = browseId.removePrefix("VL")
        val id = Downloads.collections.value.keys.firstOrNull { it.removePrefix("VL") == bare } ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * The Cached songs folder: the YouTube and JioSaavn tracks the song cache
     * is holding, most recently played first. See [AudioCache.cachedSongs].
     */
    private suspend fun cachedSongsState(): UiState<List<Song>> {
        val songs = AudioCache.cachedSongs().map { it.song }
        return if (songs.isEmpty()) UiState.Error(text(R.string.cached_songs_empty)) else UiState.Success(songs)
    }

    /**
     * Applies the service-owned canonical context to its matching open page.
     *
     * The service only materialises a small queue window, while its registry
     * keeps the complete canonical list. Mirroring that registry here avoids a
     * page that appears to stop growing as soon as playback starts.
     */
    private fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) {
        val browseId = snapshot.request.source.id ?: return
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return
        val page = stack[index]
        if (page.type != BrowseType.ALBUM && page.type != BrowseType.PLAYLIST) return
        // Service-owned canonical contexts supply their own verified continuation.
        // The retired YouTube continuation loader must never be resumed here.
        val existing = (page.songs as? UiState.Success)?.data ?: return
        val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
        val added = snapshot.songs
            .filter { known.add(it.contextEntryKey()) }
            .withArtwork(page.thumbnailUrl)
        // Never move the page's token backwards: a page whose own fill-in is
        // ahead of the service would otherwise hand a later tap a token for
        // songs it already has, which the registry rejects as a duplicate page.
        val continuation = if (snapshot.songs.size >= existing.size) snapshot.continuationToken else page.continuation
        if (added.isEmpty() && page.continuation == continuation) return
        _detailStack.value = stack.toMutableList().also {
            it[index] = page.copy(
                songs = UiState.Success(existing + added),
                continuation = continuation,
            )
        }
    }

    /**
     * Returns the newest page snapshot for a bounded playback handoff. The
     * page's own fill-in keeps running until the service reports that it has
     * taken the continuation over (see [applyBoundedContextSnapshot]); the
     * tap may yet go down another path, and cancelling here left the page
     * permanently cut short whenever it did.
     */
    fun takePlaybackContext(browseId: String): DetailPage? {
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return null
        val mirrored = BoundedContextRequests.snapshot.value
            ?.takeIf { it.request.source.id == browseId }
        if (mirrored != null) {
            applyBoundedContextSnapshot(mirrored)
            return _detailStack.value.getOrNull(index)
        }
        return stack[index]
    }

    /**
     * An album's track listing doesn't repeat the cover on every row — the
     * page carries it once — so rows arrive with no artwork and stay blank
     * through to the queue and the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /**
     * Home and Explore cards don't say what they point at, and an artist
     * fetched as an album only yields the five songs on its landing page.
     * YouTube's browse ids are prefixed by kind, so use that.
     *
     * Public because the long-press menus ask the same question of a card
     * before offering to queue what is behind it — an artist is not a running
     * order, so it gets no queue actions.
     */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    /**
     * Every track behind an album or playlist, handed to [onResult] once it is
     * all in.
     *
     * What a long-press on a card is acting on. A card has nothing but a browse
     * id — its page was never opened, so there is no track list anywhere to
     * read — and "add this album to the queue" means the whole album, so this
     * follows continuations to the end rather than taking the first page.
     *
     * Runs in [viewModelScope], not the caller's: the sheet the tap came from
     * closes immediately, and a three-hundred-track playlist must not be
     * abandoned halfway because of it.
     */
    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = when (val local = localPageState(browseId)) {
                is UiState.Success -> Result.success(local.data)
                is UiState.Error -> Result.failure(IllegalStateException(local.message))
                UiState.Loading -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
                null -> Result.failure(IllegalStateException(text(R.string.legacy_library_youtube_disabled)))
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        val wasCanonical = stack.last().browseId.startsWith("catalog:")
        _detailStack.value = stack.dropLast(1)
        if (wasCanonical) backCanonicalCatalogEntry()
        return true
    }

    /**
     * Loads the channel list, unless it is already in hand.
     *
     * @param force refetch even if it is — after a switch, since the list
     *   itself reports which channel is active.
     */
    fun loadChannels(@Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        // Historical account profiles remain decodable but cannot trigger a retired endpoint.
        // This entrypoint is kept while legacy account controls are migrated.
    }

    /**
     * Acts as [channel] from here on.
     *
     * Everything already on screen belongs to the channel being left — its
     * library, its hearts, its playlists — so the switch clears them and
     * refetches rather than letting the new identity's pages arrive one at a
     * time on top of the old one's.
     */
    fun selectChannel(channel: AccountChannel) {
        val accountId = _activeAccountId.value ?: return
        val profile = YouTubeProfile(
            profileId = profileId(channel.pageId, channel.dataSyncId, channel.name),
            name = channel.name, handle = channel.subtitle, avatar = channel.thumbnailUrl,
            pageId = channel.pageId, dataSyncId = channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        )
        selectProfile(accountId, profile.profileId, profile)
    }

    /**
     * Compatibility entrypoint for a retired YouTube Music web session.
     * Existing archived session records remain readable, but new cookies are
     * neither verified against Innertube nor persisted by this route.
     */
    fun onWebSession(
        @Suppress("UNUSED_PARAMETER") session: CapturedSession,
        @Suppress("UNUSED_PARAMETER") mode: WebSessionMode,
        onComplete: (Boolean) -> Unit = {},
    ) {
        onComplete(false)
    }

    /** Selects an identity without ever allowing a response to replace it. */
    fun selectProfile(accountId: String, selectedProfileId: String, supplied: YouTubeProfile? = null) {
        val source = _googleAccounts.value.firstOrNull { it.accountId == accountId } ?: return
        val profile = supplied ?: source.profiles.firstOrNull { it.profileId == selectedProfileId } ?: return
        if (accountId == _activeAccountId.value && profile.profileId == _activeProfileId.value) return
        cacheCurrentListener()
        val index = source.profiles.indexOfFirst { it.profileId == profile.profileId }
        val updatedProfiles = if (index >= 0) {
            source.profiles.toMutableList().apply { set(index, profile) }
        } else {
            source.profiles + profile
        }
        val updated = source.copy(
            profiles = updatedProfiles,
            activeProfileId = profile.profileId,
        )
        authStore.upsertSession(updated)
        authStore.select(accountId, profile.profileId)
        _googleAccounts.value = authStore.sessions
        _activeAccountId.value = accountId
        _activeProfileId.value = profile.profileId
        authStore.cookie = updated.cookie
        _selectedChannelKey.value = profile.profileId
        _selectedChannelName.value = profile.name
        clearListenerState(restoreCached = true)
        reloadForAccount()
    }

    /** Returns false at an edge, allowing the avatar to play its elastic cue. */
    fun stepProfile(forward: Boolean): Boolean {
        val target = adjacentProfile(_googleAccounts.value, _activeAccountId.value, _activeProfileId.value, forward)
            ?: return false
        selectProfile(target.first, target.second)
        return true
    }

    fun removeAccount(accountId: String) {
        val fallback = authStore.removeAccount(accountId)
        _googleAccounts.value = authStore.sessions
        if (fallback == null) { signOut(); return }
        selectProfile(fallback.accountId, fallback.activeProfileId ?: fallback.profiles.firstOrNull()?.profileId ?: return)
    }

    /**
     * Drops everything on screen that belongs to the identity being left.
     *
     * The hearts, the playlists and the library are all answers to "who is
     * asking", so keeping them across a switch shows the new channel the old
     * one's music until each page happens to be refetched.
     */
    private fun cacheCurrentListener() {
        val key = listenerKey() ?: return
        listenerCache[key] = ListenerSnapshot(
            _account.value, _library.value, _history.value, _playlists.value, _playlistOwned.value,
        )
    }

    private fun listenerKey(): String? = _activeAccountId.value?.let { accountId ->
        _activeProfileId.value?.let { profileId -> "$accountId:$profileId" }
    }

    private fun clearListenerState(restoreCached: Boolean = false) {
        accountLoadJob?.cancel()
        accountReloadJob?.cancel()
        libraryLoadJob?.cancel()
        playlistsLoadJob?.cancel()
        channelsLoadJob?.cancel()
        historyLoadJob?.cancel()
        libraryStateBeforeLoad = null
        historyStateBeforeLoad = null
        _account.value = null
        likedSyncJob?.cancel()
        LikeState.clear()
        _playlistsLoading.value = false
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        _songMenu.value = null
        _library.value = UiState.Loading
        _history.value = UiState.Loading
        if (restoreCached) listenerCache[listenerKey()]?.let { cached ->
            _account.value = cached.account
            _library.value = cached.library
            _history.value = cached.history
            _playlists.value = cached.playlists
            _playlistOwned.value = cached.owned
        }
    }

    /**
     * Everything that is "the signed-in listener's", refetched.
     *
     * The scope comes first, and inside one coroutine rather than beside them:
     * which channel the session acts as decides what "the library" and "the
     * history" even refer to, so loading them first and scoping second shows
     * the listener the wrong account's music and then silently disagrees with
     * itself.
     */
    private fun reloadForAccount() {
        // Legacy Google identity changes no longer activate YouTube account endpoints.
        // Canonical Home remains independent of the archived listener session.
        accountReloadJob?.cancel()
        loadHome()
    }

    fun signOut() {
        // In a multi-account install, sign out removes only the current Google
        // session and restores another one. A legacy single-account install
        // retains the familiar full sign-out behaviour.
        val current = _activeAccountId.value
        if (current != null && authStore.sessions.size > 1) {
            removeAccount(current)
            return
        }
        authStore.signOut()
        // The mirror image: verdicts reached with a session in hand say nothing
        // about what an anonymous walk will be told, and the clients stood down
        // for refusing the session deserve a fresh hearing without it.
        _signedIn.value = false
        _account.value = null
        _channels.value = emptyList()
        _selectedChannelKey.value = null
        _selectedChannelName.value = null
        _googleAccounts.value = emptyList()
        _activeAccountId.value = null
        _activeProfileId.value = null
        _library.value = UiState.Loading
        // Ratings and playlists belong to the account that just left; keeping
        // them would show the next signed-in user someone else's hearts.
        likedSyncJob?.cancel()
        LikeState.clear()
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        _songMenu.value = null
        loadHome()
    }

    private fun Throwable.friendly(): String = when {
        message?.contains("resolve host", true) == true ||
            message?.contains("Unable to resolve", true) == true -> text(R.string.no_internet_connection)
        message?.contains("401") == true || message?.contains("403") == true ->
            text(R.string.youtube_request_rejected)
        else -> message ?: text(R.string.something_went_wrong)
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}
