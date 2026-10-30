package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
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

/**
 * "Adições locais" only shows tracks with observed MediaStore addition timestamps.
 * No synthetic album or fabricated artwork from an unrelated release.
 */
@Composable
internal fun CanonicalLocalRecentAdditions(
    onOpenSongs: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val database = remember(context) { CanonicalCatalogDatabase.get(context) }
    val dao = remember(database) { database.catalogDao() }
    val recent by produceState(initialValue = emptyList<V2DisplayRow>(), key1 = dao) {
        database.invalidationTracker.createFlow(
            "catalog_media_asset", "catalog_recording",
        ).collectLatest {
            value = try {
                withContext(Dispatchers.IO) {
                    dao.recentLocalAdditions(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
                        .distinctBy { it.recordingId }
                        .take(6)
                        .map { row ->
                            V2DisplayRow(
                                id = row.recordingId,
                                title = row.title,
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
    if (recent.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Adições recentes",
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            style = MaterialTheme.typography.headlineMedium,
        )
        // The Library already has a snapping/lazy shelf; reuse it rather than
        // recreating a horizontalScroll Row for recent local recordings.
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recent, key = { it.id }) { track ->
                Column(
                    modifier = Modifier.width(142.dp).clickable(onClick = onOpenSongs),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SmartPlaylistPreviewArtwork(track, Modifier.size(142.dp))
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
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
        }
    }
}
