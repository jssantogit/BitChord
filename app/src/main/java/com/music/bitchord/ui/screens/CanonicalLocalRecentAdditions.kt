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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ShelfRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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

/**
 * "Adições locais" only shows tracks with observed MediaStore addition timestamps.
 * No synthetic album or fabricated artwork from an unrelated release.
 */
@Composable
internal fun CanonicalLocalRecentAdditions(
    onOpenSongs: () -> Unit,
    onSeeAll: () -> Unit = {},
    showAll: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current.applicationContext
    val database = remember(context) { CanonicalCatalogDatabase.get(context) }
    val dao = remember(database) { database.catalogDao() }
    val recent by produceState(initialValue = emptyList<V2DisplayRow>(), key1 = dao, key2 = showAll) {
        database.invalidationTracker.createFlow(
            "catalog_media_asset", "catalog_recording",
        ).collectLatest {
            value = try {
                withContext(Dispatchers.IO) {
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
                    recentRows.map { row ->
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
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    if (showAll) {
        CanonicalRecentAdditionsGrid(recent, onOpenSongs, contentPadding)
    } else {
        CanonicalRecentAdditionsShelf(recent, onOpenSongs, onSeeAll)
    }
}

/** A renderable shelf with an explicit heading, independently regression-tested. */
@Composable
internal fun CanonicalRecentAdditionsShelf(
    recent: List<V2DisplayRow>,
    onOpenSongs: () -> Unit,
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
                    onClick = onOpenSongs,
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
    onOpenSongs: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (recent.isEmpty()) item {
            Text(
                "Nenhuma adição local recente.",
                modifier = Modifier.padding(PAGE_GUTTER),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        gridRows(recent, columns = 2, key = { it.id }) { track ->
            CanonicalRecentAdditionCard(
                track = track, modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSongs,
            )
        }
    }
}
