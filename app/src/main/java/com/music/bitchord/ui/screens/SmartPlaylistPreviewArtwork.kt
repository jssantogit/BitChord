package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.catalog.provider.opensubsonic.androidOpenSubsonicArtworkLoader

/**
 * A preview may show only artwork tied to the exact canonical RecordingId
 * through an eligible MediaAsset, never an album inferred by title/artist.
 * The remote loader authenticates internally and does not expose signed URLs.
 */
@Composable
internal fun SmartPlaylistPreviewArtwork(
    row: V2DisplayRow,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loader = remember(context.applicationContext) {
        androidOpenSubsonicArtworkLoader(context.applicationContext)
    }
    when {
        row.localArtworkUri != null -> AsyncImage(
            model = row.localArtworkUri,
            contentDescription = row.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
        row.remoteMediaAssetId != null && row.remoteArtworkRevision != null ->
            RemoteTrackArtwork(
                mediaAssetId = row.remoteMediaAssetId,
                artworkRevision = row.remoteArtworkRevision,
                title = row.title,
                artworkLoader = loader,
                modifier = modifier,
            )
        else -> Surface(
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp))
            }
        }
    }
}
