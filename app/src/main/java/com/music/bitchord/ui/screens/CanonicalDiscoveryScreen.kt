package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.data.model.UiState

internal enum class CanonicalDiscoverySurface { HOME, EXPLORE }

/** Saved device collections and catalog metadata; no editorial or synthetic rows. */
@Composable
internal fun CanonicalDiscoveryScreen(
    surface: CanonicalDiscoverySurface,
    catalog: UiState<List<CanonicalCatalogSearchRow>>,
    downloadedCollections: List<SavedCollection>,
    localPlaylists: List<LocalPlaylist>,
    onSearchCatalog: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloadedCollection: (SavedCollection) -> Unit,
    onOpenLocalPlaylist: (LocalPlaylist) -> Unit,
    onOpenCatalogEntry: (CanonicalCatalogSearchRow) -> Unit,
    onRetryCatalog: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onPlayRecording: (String) -> Unit,
    onQueueRecording: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("canonical-discovery-list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "canonical-discovery-header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(
                        if (surface == CanonicalDiscoverySurface.HOME) {
                            R.string.canonical_discovery_home_title
                        } else R.string.canonical_discovery_explore_title,
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.canonical_discovery_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "canonical-discovery-actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSearchCatalog) {
                    Text(stringResource(R.string.canonical_discovery_search))
                }
                OutlinedButton(onClick = onOpenLibrary) {
                    Text(stringResource(R.string.canonical_discovery_open_library))
                }
                OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(stringResource(R.string.canonical_discovery_refresh))
                }
            }
        }

        if (surface == CanonicalDiscoverySurface.HOME) {
            item(key = "canonical-downloads-heading") {
                SectionHeading(stringResource(R.string.canonical_discovery_downloaded_collections))
            }
            if (downloadedCollections.isEmpty()) {
                item(key = "canonical-downloads-empty") {
                    EmptyMessage(stringResource(R.string.canonical_discovery_empty_collections))
                }
            } else {
                items(downloadedCollections, key = { "saved:${it.id}" }) { collection ->
                    CollectionCard(
                        title = collection.title,
                        subtitle = collection.subtitle,
                        onClick = { onOpenDownloadedCollection(collection) },
                    )
                }
            }
            item(key = "canonical-local-playlists-heading") {
                SectionHeading(stringResource(R.string.canonical_discovery_local_playlists))
            }
            if (localPlaylists.isEmpty()) {
                item(key = "canonical-local-playlists-empty") {
                    EmptyMessage(stringResource(R.string.canonical_discovery_empty_collections))
                }
            } else {
                items(localPlaylists, key = { "playlist:${it.id}" }) { playlist ->
                    CollectionCard(
                        title = playlist.title,
                        subtitle = stringResource(R.string.card_songs_format, playlist.songs.size),
                        onClick = { onOpenLocalPlaylist(playlist) },
                    )
                }
            }
        }
        // The Home screen must not hide the canonical catalog. Saved local
        // collections and persisted MusicBrainz identities are complementary:
        // the latter retain offline artist/release navigation without YouTube.
        run {
            item(key = "canonical-catalog-heading") {
                SectionHeading(stringResource(R.string.canonical_discovery_catalog_cache))
            }
            when (catalog) {
                UiState.Loading -> item(key = "canonical-catalog-loading") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.canonical_discovery_cache_loading),
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                        )
                    }
                }
                is UiState.Error -> item(key = "canonical-catalog-error") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            text = catalog.message.ifBlank {
                                stringResource(R.string.canonical_discovery_cache_error)
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onRetryCatalog) {
                            Text(stringResource(R.string.canonical_discovery_retry))
                        }
                    }
                }
                is UiState.Success -> if (catalog.data.isEmpty()) {
                    item(key = "canonical-catalog-empty") {
                        EmptyMessage(stringResource(R.string.canonical_discovery_cache_empty))
                    }
                } else {
                    items(catalog.data, key = { "mb:${it.kind}:${it.musicBrainzId}" }) { row ->
                        CatalogCard(
                            row = row,
                            onOpen = { onOpenCatalogEntry(row) },
                            onPlay = { onPlayRecording(row.musicBrainzId) },
                            onQueue = { onQueueRecording(row.musicBrainzId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
    )
}

@Composable
private fun EmptyMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun CollectionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CatalogCard(
    row: CanonicalCatalogSearchRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    val navigable = row.kind != CanonicalSearchKind.RECORDING
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .then(if (navigable) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val kindLabel = when (row.kind) {
                CanonicalSearchKind.RECORDING -> "Recording"
                CanonicalSearchKind.RELEASE_GROUP -> "Release group"
                CanonicalSearchKind.RELEASE -> "Release edition"
                CanonicalSearchKind.ARTIST -> "Artist"
            }
            Text(
                text = listOfNotNull(kindLabel, row.subtitle?.takeIf(String::isNotBlank))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.fromOfflineCache) {
                Text(
                    stringResource(R.string.canonical_discovery_cached_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.kind == CanonicalSearchKind.RECORDING) {
                Text(
                    stringResource(
                        if (row.hasCatalogedAsset) R.string.canonical_discovery_asset_available
                        else R.string.canonical_discovery_metadata_only,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.hasCatalogedAsset) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPlay) { Text(stringResource(R.string.play)) }
                        OutlinedButton(onClick = onQueue) { Text(stringResource(R.string.add_to_queue)) }
                    }
                }
            }
        }
    }
}
