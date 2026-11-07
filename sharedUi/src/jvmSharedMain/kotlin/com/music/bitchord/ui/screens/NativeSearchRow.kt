package com.music.bitchord.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.thumbnailBorder
import org.jetbrains.compose.resources.stringResource

/**
 * Pure search row presentation. Does not contain a browseId, Song.videoId,
 * provider ID, stream URL or playback entitlement. Those belong to the source
 * adapter and its explicit onClick callback.
 */
data class NativeSearchRowUi(
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val artistPortrait: Boolean,
    val downloadBadge: Boolean = false,
    val durationLabel: String? = null,
)

/**
 * Original 52dp native search row, now shared by legacy Search, MusicBrainz
 * public metadata search and server/local search. The caller decides whether
 * the row can open, play or show long-press actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NativeSearchResultRow(
    row: NativeSearchRowUi,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = PAGE_GUTTER,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) {
                Modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                    .contextClick(onLongPress)
            } else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            val shape = if (row.artistPortrait) CircleShape else RoundedCornerShape(8.dp)
            AsyncImage(
                model = row.artworkUri.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(shape)
                    .thumbnailBorder(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (row.downloadBadge && !row.artistPortrait) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = stringResource(Res.string.downloaded),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        row.durationLabel?.let { duration ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
