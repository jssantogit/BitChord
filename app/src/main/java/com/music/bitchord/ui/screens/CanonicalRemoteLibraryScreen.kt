package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.remember
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
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryBrowseRepository
import com.music.bitchord.data.catalog.RoomCanonicalLibraryBrowseStore

/**
 * Offline-first browse for a provider's persisted canonical availability.
 *
 * The provider key is only a Room availability filter. This screen never
 * constructs a legacy Song, never reads OpenSubsonic credentials and never
 * contacts the provider. Visible row identity is the canonical recording id.
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

    BackHandler(onBack = onClose)

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
            RemoteBrowseTopBar(title = title, onBack = onClose)
            RemoteBrowseSummary(songCount = library.tracks.size)
            TrackList(
                tracks = library.tracks,
                modifier = Modifier.fillMaxSize(),
            )
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
            Text(
                text = stringResource(R.string.remote_library_playback_deferred),
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
private fun CanonicalTrackRow(track: CanonicalBrowseTrack) {
    // Deliberately not clickable: remote playback belongs to PlaybackResolver.
    // Making this row playable now would require fabricating a legacy Song id.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ArtworkPlaceholder(
            hasArtworkReference = !track.artworkUri.isNullOrBlank(),
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
private fun ArtworkPlaceholder(
    hasArtworkReference: Boolean,
    modifier: Modifier = Modifier,
) {
    // OpenSubsonic artwork is currently a logical credential-free reference,
    // not an offline cached bitmap. Resolving it here would make simple browse
    // a network/auth operation, so this slice intentionally renders a bounded
    // placeholder while preserving the reference in the read model.
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
