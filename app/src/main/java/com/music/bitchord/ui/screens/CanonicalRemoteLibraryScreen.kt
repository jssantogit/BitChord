package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseRelease
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryBrowseRepository
import com.music.bitchord.data.catalog.RoomCanonicalLibraryBrowseStore
import kotlin.math.max

/**
 * Offline-first browse for a provider's persisted canonical availability.
 *
 * This screen deliberately has no OpenSubsonic adapter, account credential,
 * sourceKey, playback resolver or Song conversion. The provider key is only a
 * Room availability filter; every visible musical identity is canonical.
 */
@Composable
internal fun CanonicalRemoteLibraryScreen(
    providerKey: String,
    title: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        CanonicalLibraryBrowseRepository(
            RoomCanonicalLibraryBrowseStore(
                CanonicalCatalogDatabase.get(context.applicationContext).catalogDao(),
            ),
        )
    }
    val libraryFlow = remember(repository, providerKey) { repository.observe(providerKey) }
    val library by libraryFlow.collectAsStateWithLifecycle(initialValue = CanonicalBrowseLibrary.EMPTY)

    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_SONGS) }
    var selectedReleaseId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRelease = remember(selectedReleaseId, library.releases) {
        selectedReleaseId?.let { id -> library.releases.firstOrNull { it.releaseId == id } }
    }

    BackHandler {
        if (selectedReleaseId != null) {
            selectedReleaseId = null
        } else {
            onClose()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            RemoteBrowseTopBar(
                title = selectedRelease?.title ?: title,
                onBack = {
                    if (selectedReleaseId != null) selectedReleaseId = null else onClose()
                },
            )

            if (selectedRelease != null) {
                ReleaseDetail(
                    release = selectedRelease,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RemoteBrowseSummary(
                    songCount = library.tracks.size,
                    releaseCount = library.releases.size,
                )
                BrowseTabs(
                    selectedTab = selectedTab,
                    onSelected = { selectedTab = it },
                )
                when (selectedTab) {
                    TAB_ALBUMS -> ReleaseList(
                        releases = library.releases,
                        onRelease = { selectedReleaseId = it.releaseId },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> TrackList(
                        tracks = library.tracks,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
private fun RemoteBrowseSummary(
    songCount: Int,
    releaseCount: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
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
                text = stringResource(
                    R.string.remote_library_counts,
                    songCount,
                    releaseCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.remote_library_offline_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrowseTabs(
    selectedTab: Int,
    onSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .padding(3.dp),
    ) {
        BrowseTab(
            label = stringResource(R.string.remote_library_songs),
            selected = selectedTab == TAB_SONGS,
            onClick = { onSelected(TAB_SONGS) },
            modifier = Modifier.weight(1f),
        )
        BrowseTab(
            label = stringResource(R.string.remote_library_albums),
            selected = selectedTab == TAB_ALBUMS,
            onClick = { onSelected(TAB_ALBUMS) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrowseTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent
    val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(background, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun TrackList(
    tracks: List<CanonicalBrowseTrack>,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        EmptyRemoteLibrary(modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        items(
            items = tracks,
            key = CanonicalBrowseTrack::recordingId,
        ) { track ->
            CanonicalTrackRow(track = track)
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun ReleaseList(
    releases: List<CanonicalBrowseRelease>,
    onRelease: (CanonicalBrowseRelease) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (releases.isEmpty()) {
        EmptyRemoteLibrary(modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        items(
            items = releases,
            key = CanonicalBrowseRelease::releaseId,
        ) { release ->
            CanonicalReleaseRow(
                release = release,
                onClick = { onRelease(release) },
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
private fun ReleaseDetail(
    release: CanonicalBrowseRelease,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "release:${release.releaseId}:header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                ArtworkPlaceholder(
                    hasArtworkReference = !release.artworkUri.isNullOrBlank(),
                    album = true,
                    modifier = Modifier.size(92.dp),
                )
                Text(
                    text = release.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp),
                )
                release.artistDisplay?.takeIf(String::isNotBlank)?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.remote_library_track_count, release.tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(
            items = release.tracks,
            key = { membership -> membership.track.recordingId },
        ) { membership ->
            CanonicalTrackRow(
                track = membership.track,
                trackNumber = membership.trackNumber,
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
        item(key = "release:${release.releaseId}:playback-note") {
            PlaybackDeferredNote()
        }
    }
}

@Composable
private fun CanonicalTrackRow(
    track: CanonicalBrowseTrack,
    trackNumber: Int? = null,
) {
    // Deliberately no clickable modifier: remote playback is owned by the
    // PlaybackResolver slice and this browse must not invent a legacy Song.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ArtworkPlaceholder(
            hasArtworkReference = !track.artworkUri.isNullOrBlank(),
            album = false,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(16.dp))
        if (trackNumber != null) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
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
        formatDuration(track.durationMs)?.let { duration ->
            Spacer(Modifier.width(12.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CanonicalReleaseRow(
    release: CanonicalBrowseRelease,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ArtworkPlaceholder(
            hasArtworkReference = !release.artworkUri.isNullOrBlank(),
            album = true,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = release.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = release.artistDisplay?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.remote_library_album),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.remote_library_track_count, release.tracks.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArtworkPlaceholder(
    hasArtworkReference: Boolean,
    album: Boolean,
    modifier: Modifier = Modifier,
) {
    // OpenSubsonic artwork currently persists as a credential-free logical URI,
    // not an offline cached bitmap. Do not resolve that URI here: doing so would
    // turn Library browse into a network/auth operation. Keep the reference in
    // the read model for the future artwork-cache boundary and render a bounded
    // placeholder meanwhile.
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
                imageVector = if (album) Icons.Rounded.Album else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (hasArtworkReference) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun EmptyRemoteLibrary(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = stringResource(R.string.remote_library_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PlaybackDeferredNote() {
    Text(
        text = stringResource(R.string.remote_library_playback_deferred),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
    )
}

private fun formatDuration(durationMs: Long?): String? {
    val value = durationMs?.takeIf { it >= 0L } ?: return null
    val totalSeconds = value / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = max(0L, totalSeconds % 60L)
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val TAB_SONGS = 0
private const val TAB_ALBUMS = 1
