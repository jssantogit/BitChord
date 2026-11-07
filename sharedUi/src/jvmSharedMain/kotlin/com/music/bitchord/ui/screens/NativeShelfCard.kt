package com.music.bitchord.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.icons.BitChordIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The original Home/Explore shelf presentation, with no ShelfItem, browseId,
 * Song.videoId or service identity. Artwork is supplied by the source adapter,
 * allowing special local folders, canonical covers and future providers to
 * share one card instead of rebuilding visually similar cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativeShelfCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    isPinned: Boolean = false,
    isCurrent: Boolean = false,
    artwork: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        artwork()
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = BitChordIcons.Pin,
                    contentDescription = stringResource(Res.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
