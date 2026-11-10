package com.music.bitchord.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.NativeMediaCardMetrics
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * The approved Apple-style featured release card. Both online artist
 * shelves and verified Library releases use the same glass, typography,
 * artwork clipping and gestures. Provider IDs, selection, pin and save
 * actions never cross into this visual component.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativeArtistTopReleaseCard(
    title: String,
    subtitle: String,
    label: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = NativeMediaCardMetrics.featuredShape
    val coverShape = NativeMediaCardMetrics.squareCoverShape
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = 22.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = NativeMediaCardMetrics.featuredFillAlpha), shape)
            .border(1.dp, Color.White.copy(alpha = NativeMediaCardMetrics.featuredEdgeAlpha), shape)
            .clip(shape)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(NativeMediaCardMetrics.featuredArtworkSize)
                .clip(coverShape)
                .border(1.dp, Color.White.copy(alpha = NativeMediaCardMetrics.featuredEdgeAlpha), coverShape)
                .background(palette.elevated),
        ) {
            artwork()
        }
        Spacer(Modifier.width(NativeMediaCardMetrics.featuredArtworkGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = palette.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}
