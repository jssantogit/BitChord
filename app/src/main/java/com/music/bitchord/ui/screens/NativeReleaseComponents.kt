package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.SourceQuality
import com.music.bitchord.ui.components.SourceQualityBadge
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * Source-independent native release UI. The old DetailPage remains only in a
 * legacy adapter; albums from Library, online catalog and playlists share
 * these original controls, artwork slots, quality/rating and contextual plus.
 */
internal val HEADER_GUTTER = PAGE_GUTTER + 8.dp
internal val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val SLEEVE_SHADOW = 18.dp
private val RELEASE_SIDE_BUTTON = 50.dp
private val RELEASE_ACTION_GAP = 14.dp
private val RELEASE_ACTIONS_MAX_WIDTH = 420.dp

@Composable
internal fun NativeReleaseActionRow(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    isLocal: Boolean,
    canShuffle: Boolean = true,
    isSaved: Boolean? = null,
    onToggleLibrary: (() -> Unit)? = null,
    // Playlist pages share release geometry, but the trailing action can queue.
    onAddToQueue: (() -> Unit)? = null,
    // Reserved for a future pin on the unified Library landing page.
    // Never attach playlist membership to the hero +.
    reservePinAction: Boolean = false,
    isPinnedInLibrary: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .widthIn(max = RELEASE_ACTIONS_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(RELEASE_ACTION_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLocal) {
            ActionPill(
                icon = BitChordIcons.Play,
                label = stringResource(R.string.play),
                onClick = onPlay,
                filled = true,
                size = RELEASE_SIDE_BUTTON,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                icon = BitChordIcons.Shuffle,
                label = stringResource(R.string.shuffle),
                onClick = onShuffle,
                filled = false,
                size = RELEASE_SIDE_BUTTON,
                modifier = Modifier.weight(1f),
            )
        } else {
            CircleIconButton(
                icon = BitChordIcons.Shuffle,
                contentDescription = stringResource(R.string.shuffle),
                onClick = onShuffle,
                haptic = Haptic.Resume,
                size = RELEASE_SIDE_BUTTON,
                enabled = canShuffle,
            )
            ActionPill(
                icon = BitChordIcons.Play,
                label = stringResource(R.string.play),
                onClick = onPlay,
                filled = true,
                size = RELEASE_SIDE_BUTTON,
                modifier = Modifier.weight(1f),
            )
            CircleIconButton(
                icon = if (reservePinAction && isPinnedInLibrary ||
                    !reservePinAction && onAddToQueue == null && isSaved == true
                ) BitChordIcons.Check else BitChordIcons.Plus,
                contentDescription = if (onAddToQueue != null) {
                    "Adicionar à fila"
                } else if (reservePinAction) {
                    if (isPinnedInLibrary) "Desafixar álbum da Biblioteca"
                    else if (onTogglePin == null) "Fixar álbum na Biblioteca (em breve)"
                    else "Fixar álbum na Biblioteca"
                } else stringResource(
                    if (isSaved == true) R.string.remove_from_library
                    else R.string.add_to_library,
                ),
                onClick = {
                    when {
                        onAddToQueue != null -> onAddToQueue()
                        reservePinAction -> onTogglePin?.invoke()
                        else -> onToggleLibrary?.invoke()
                    }
                },
                haptic = when {
                    onAddToQueue != null -> Haptic.Resume
                    reservePinAction && isPinnedInLibrary -> Haptic.ToggleOff
                    !reservePinAction && isSaved == true -> Haptic.ToggleOff
                    else -> Haptic.ToggleOn
                },
                size = RELEASE_SIDE_BUTTON,
                enabled = when {
                    onAddToQueue != null -> true
                    reservePinAction -> onTogglePin != null
                    else -> onToggleLibrary != null
                },
            )
        }
    }
}

@Composable
internal fun NativeReleaseHeaderLayout(
    title: String,
    credit: String,
    meta: String,
    palette: ArtworkPalette,
    rating: Double? = null,
    quality: SourceQuality? = null,
    onRate: (() -> Unit)? = null,
    sleeveSize: Dp,
    artwork: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    topInset: Dp? = null,
    onCreditClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(top = topInset ?: topBarContentPadding(), bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(sleeveSize)
                .shadow(SLEEVE_SHADOW, SLEEVE_SHAPE)
                .background(palette.elevated)
                .thumbnailBorder(SLEEVE_SHAPE),
            content = artwork,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HEADER_GUTTER),
        )
        if (credit.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = credit,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal, fontSize = 19.sp,
                ),
                color = palette.onBackgroundVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER)
                    .let { m -> if (onCreditClick == null) m
                        else m.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCreditClick) },
            )
        }
        // The original rating slot shows first-party stars when saved. With
        // no rating it uses the exact Now Playing source badge; never invent
        // "Hi-Res" from bitrate or claim provider evidence was played.
        if (rating != null && onRate != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRate)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("native-album-rating"),
            ) {
                CanonicalRatingStars(
                    rating = RatingDisplay(localValue = rating),
                    color = palette.onBackground,
                )
            }
        } else if (rating == null && quality != null) {
            Spacer(Modifier.height(8.dp))
            SourceQualityBadge(
                quality = quality,
                color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("native-album-source-quality"),
            )
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = palette.onBackgroundVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER),
            )
        }
        actions()
    }
}


