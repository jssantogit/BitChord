package com.music.bitchord.ui.screens

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.UnifiedLocalCatalogIndex
import com.music.bitchord.data.catalog.CanonicalEntitySortDateRow
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.ui.theme.themeArtworkPalette
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ShelfRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prefer a real music title; use verified Release title only for artist-only tags. */
internal fun preferRecentLocalAdditionTitle(
    title: String, artist: String, albumTitle: String?,
): String = if (title.isBlank() || title.equals(artist, ignoreCase = true)) {
    albumTitle?.takeIf { it.isNotBlank() } ?: title
} else title

/** Never show an artist name as the apparent title of the latest addition. */
internal fun recentLocalPreferredDisplayTitle(
    canonicalTitle: String,
    artist: String,
    releaseTitle: String?,
    mediaStoreTitle: String?,
    mediaStoreAlbum: String?,
    displayName: String?,
): String {
    val filename = displayName?.substringBeforeLast('.')?.trim()
    return listOf(canonicalTitle, mediaStoreTitle, releaseTitle, mediaStoreAlbum, filename)
        .firstOrNull { candidate ->
            !candidate.isNullOrBlank() &&
                !candidate.trim().equals(artist.trim(), ignoreCase = true) &&
                !candidate.trim().equals("<unknown>", ignoreCase = true) &&
                !candidate.trim().equals("unknown", ignoreCase = true)
        }?.trim() ?: "Título indisponível"
}

/** Actual MediaStore data, queried only for the six observed local additions. */
private fun localRecentMediaStoreLabels(
    context: android.content.Context,
    uri: String?,
): Triple<String?, String?, String?> {
    if (uri.isNullOrBlank() || !uri.startsWith("content://")) {
        return Triple(null, null, null)
    }
    return try {
        context.contentResolver.query(
            Uri.parse(uri),
            arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use Triple(null, null, null)
            fun read(name: String): String? =
                cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getString(it) }
            Triple(
                read(MediaStore.Audio.Media.TITLE),
                read(MediaStore.Audio.Media.ALBUM),
                read(MediaStore.Audio.Media.DISPLAY_NAME),
            )
        } ?: Triple(null, null, null)
    } catch (_: Exception) {
        Triple(null, null, null)
    }
}

/** A process-local snapshot avoids re-querying and re-reading MediaStore on every navigation. */
/** An empty pre-index snapshot must not permanently hide the recent shelf. */
internal fun shouldRequeryRecentLocalAdditions(
    cachedIds: List<String>?,
    explicitRefresh: Boolean,
): Boolean = explicitRefresh || cachedIds.isNullOrEmpty()

/** Native list filters never trigger a new filesystem or Room read. */
internal fun filteredCanonicalRecentLocalAdditions(
    tracks: List<V2DisplayRow>,
    query: String,
    sort: LocalMusicSort,
    sourceKey: String?,
    stars: Int?,
    ratings: CanonicalRatingDisplaySnapshot,
    dates: Map<String, CanonicalEntitySortDateRow>,
): List<V2DisplayRow> {
    require(stars == null || stars in 1..5)
    if (sourceKey != null && sourceKey != CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
        return emptyList()
    val term = query.trim()
    val visible = tracks.filter { track ->
        (term.isEmpty() || track.title.contains(term, ignoreCase = true) ||
            track.artist.contains(term, ignoreCase = true)) &&
            (stars == null || ratings.byRecordingId[track.id]?.localValue == stars.toDouble())
    }
    // Use EXACTLY the same native Library sort planner as Músicas, Álbuns and
    // Artistas. No parallel comparator or second date interpretation here.
    // A snapshot from older app sessions may have rows but not dates yet:
    // keep its true MediaStore newest-first insertion order until refreshed.
    if (dates.isEmpty() && sort in setOf(
        LocalMusicSort.DATE_ADDED, LocalMusicSort.DATE_MODIFIED,
    )) return visible
    return sortCanonicalUnifiedEntities(
        entries = visible,
        sort = sort,
        dates = dates,
        id = V2DisplayRow::id,
        title = V2DisplayRow::title,
    )
}

internal object RecentLocalAdditionsSnapshot {
    private var all: List<V2DisplayRow>? = null
    private var shelf: List<V2DisplayRow>? = null
    private var timestamps = emptyMap<String, CanonicalEntitySortDateRow>()

    @Synchronized fun dates(): Map<String, CanonicalEntitySortDateRow> = timestamps

    @Synchronized fun get(showAll: Boolean): List<V2DisplayRow>? =
        if (showAll) all else shelf ?: all?.take(6)

    @Synchronized fun put(
        showAll: Boolean,
        rows: List<V2DisplayRow>,
        realDates: Map<String, CanonicalEntitySortDateRow> = emptyMap(),
    ) {
        if (realDates.isNotEmpty()) timestamps = timestamps + realDates
        if (showAll) {
            all = rows
            shelf = rows.take(6)
        } else shelf = rows
    }

    @Synchronized fun clear() {
        all = null
        shelf = null
        timestamps = emptyMap()
    }
}

/**
 * "Adições locais" only shows tracks with observed MediaStore addition timestamps.
 * No synthetic album or fabricated artwork from an unrelated release.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CanonicalLocalRecentAdditions(
    onPlayTrack: (String) -> Unit,
    onSeeAll: () -> Unit = {},
    showAll: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    refreshRequested: Boolean = false,
    searchVisible: Boolean = false,
    sort: LocalMusicSort = LocalMusicSort.DATE_ADDED,
    viewType: LibraryViewType = LibraryViewType.GRID,
    sourceKey: String? = null,
    stars: Int? = null,
) {
    val context = LocalContext.current.applicationContext
    val database = remember(context) { CanonicalCatalogDatabase.get(context) }
    val dao = remember(database) { database.catalogDao() }
    var refreshSerial by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var searchQuery by remember(showAll) { mutableStateOf("") }
    LaunchedEffect(searchVisible) { if (!searchVisible) searchQuery = "" }
    val ratingSnapshot = LocalCanonicalRatingDisplay.current
    val recent by produceState<List<V2DisplayRow>?>(
        initialValue = RecentLocalAdditionsSnapshot.get(showAll)?.takeIf { it.isNotEmpty() },
        key1 = dao, key2 = showAll, key3 = refreshSerial to refreshRequested,
    ) {
        val prior = RecentLocalAdditionsSnapshot.get(showAll)
        if (!shouldRequeryRecentLocalAdditions(
                prior?.map { it.id }, explicitRefresh = refreshSerial > 0 || refreshRequested,
            )
        ) {
            value = prior
            return@produceState
        }
        refreshing = refreshSerial > 0
        try {
            val (fresh, factDates) = withContext(Dispatchers.IO) {
                    // Home used to read an empty Room table before the first
                    // unified-category visit indexed MediaStore. Index once
                    // before reading; an empty pre-index cache is not final.
                    UnifiedLocalCatalogIndex.ensure(context)
                    val recentRows = dao.recentLocalAdditions(
                        CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                        limit = if (showAll) Int.MAX_VALUE else 12,
                    ).distinctBy { it.recordingId }.let { rows ->
                        if (showAll) rows else rows.take(6)
                    }
                    // Full history can exceed SQLite's bind-variable limit.
                    val albums = recentRows.chunked(300).flatMap { batch ->
                        dao.recentLocalReleaseTitles(batch.map { it.recordingId })
                    }.associate { it.recordingId to it.albumTitle }
                    val assets = recentRows.chunked(300).flatMap { batch ->
                        dao.mediaAssetsByIds(batch.map { it.mediaAssetId })
                    }.associateBy { it.id }
                    val viewRows = recentRows.map { row ->
                        val (mediaTitle, mediaAlbum, fileName) = localRecentMediaStoreLabels(
                            context, assets[row.mediaAssetId]?.uri,
                        )
                        V2DisplayRow(
                            id = row.recordingId,
                            title = recentLocalPreferredDisplayTitle(
                                canonicalTitle = row.title,
                                artist = row.artistDisplay,
                                releaseTitle = albums[row.recordingId],
                                mediaStoreTitle = mediaTitle,
                                mediaStoreAlbum = mediaAlbum,
                                displayName = fileName,
                            ),
                            artist = row.artistDisplay,
                            localArtworkUri = row.artworkUri,
                        )
                    }
                    viewRows to recentRows.associate { row ->
                        row.recordingId to CanonicalEntitySortDateRow(
                            entityId = row.recordingId,
                            dateAddedSeconds = row.dateAddedSeconds,
                            dateModifiedSeconds = row.dateModifiedSeconds,
                        )
                    }
            }
            RecentLocalAdditionsSnapshot.put(showAll, fresh, factDates)
            value = fresh
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
            value = RecentLocalAdditionsSnapshot.get(showAll)
        } finally {
            refreshing = false
        }
    }
    if (showAll) {
        // The same pull-to-refresh gesture as the Library, but explicit refresh
        // is the only reason to repeat the expensive local catalog projection.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshSerial++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            CanonicalRecentAdditionsGrid(
                recent = filteredCanonicalRecentLocalAdditions(
                    tracks = recent.orEmpty(),
                    query = searchQuery,
                    sort = sort,
                    sourceKey = sourceKey,
                    stars = stars,
                    ratings = ratingSnapshot,
                    dates = RecentLocalAdditionsSnapshot.dates(),
                ),
                onPlayTrack = onPlayTrack,
                contentPadding = contentPadding,
                loading = recent == null && !loadFailed,
                error = loadFailed,
                showSearch = searchVisible,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                viewType = viewType,
                filterActive = searchQuery.isNotBlank() || stars != null ||
                    sourceKey != null,
            )
        }
    } else if (recent != null) {
        CanonicalRecentAdditionsShelf(recent.orEmpty(), onPlayTrack, onSeeAll)
    } else {
        Text(
            if (loadFailed) "Não foi possível consultar as adições locais."
            else "Carregando adições recentes…",
            modifier = Modifier.padding(PAGE_GUTTER),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A renderable shelf with an explicit heading, independently regression-tested. */
@Composable
internal fun CanonicalRecentAdditionsShelf(
    recent: List<V2DisplayRow>,
    onPlayTrack: (String) -> Unit,
    onSeeAll: () -> Unit = {},
) {
    if (recent.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Adições recentes",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onSeeAll) { Text("Ver todos") }
        }
        // The Library already has a snapping/lazy shelf; reuse it rather than
        // recreating a horizontalScroll Row for recent local recordings.
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recent, key = { it.id }) { track ->
                CanonicalRecentAdditionCard(
                    track = track,
                    modifier = Modifier.width(142.dp),
                    onClick = { onPlayTrack(track.id) },
                )
            }
        }
    }
}

/** The same canonical recording card is reused in the shelf and existing folder grid. */
@Composable
private fun CanonicalRecentAdditionCard(
    track: V2DisplayRow,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            SmartPlaylistPreviewArtwork(track, Modifier.fillMaxSize())
        }
        Text(
            track.title.takeIf { it.isNotBlank() } ?: "Título indisponível",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Reuses LocalFolder's canonical two-column grid layout and card. */
@Composable
internal fun CanonicalRecentAdditionsGrid(
    recent: List<V2DisplayRow>,
    onPlayTrack: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    loading: Boolean = false,
    error: Boolean = false,
    showSearch: Boolean = false,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    viewType: LibraryViewType = LibraryViewType.GRID,
    filterActive: Boolean = false,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (showSearch) item(key = "recent-search") {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = {},
                placeholder = "Pesquisar em adições recentes",
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        if (recent.isEmpty()) item {
            Text(
                when {
                    loading -> "Carregando adições locais…"
                    error -> "Não foi possível consultar as adições locais. Arraste para atualizar."
                    filterActive -> "Nenhuma adição corresponde aos filtros."
                    else -> "Nenhuma adição local recente."
                },
                modifier = Modifier.padding(PAGE_GUTTER),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (viewType == LibraryViewType.LIST) {
            items(recent, key = { it.id }) { track ->
                LibraryRow(
                    title = track.title,
                    subtitle = track.artist,
                    imageUrl = null,
                    artwork = {
                        SmartPlaylistPreviewArtwork(track, Modifier.fillMaxSize())
                    },
                    onClick = { onPlayTrack(track.id) },
                    horizontalPadding = PAGE_GUTTER,
                    artworkSize = 52.dp,
                )
            }
        } else {
            gridRows(recent, columns = 2, key = { it.id }) { track ->
                CanonicalRecentAdditionCard(
                    track = track, modifier = Modifier.fillMaxWidth(),
                    onClick = { onPlayTrack(track.id) },
                )
            }
        }
    }
}
