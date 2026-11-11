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
import androidx.compose.foundation.layout.width
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
import com.music.bitchord.ui.components.CompactRatingGeometry
import com.music.bitchord.ui.components.CompactRatingIndicator
import com.music.bitchord.ui.components.normalizedCompactRating
import com.music.bitchord.ui.components.PAGE_GUTTER
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
    canPlay: Boolean = true,
    canShuffle: Boolean = true,
    isSaved: Boolean? = null,
    onToggleLibrary: (() -> Unit)? = null,
    // Playlist pages share release geometry, but the trailing action can queue.
    onAddToQueue: (() -> Unit)? = null,
    // Reserved for a future pin on the unified Library landing page.
    // Never attach playlist membership to the hero +.
    reservePinAction: Boolean = false,
    pinTargetLabel: String = "álbum",
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
                enabled = canPlay,
                size = RELEASE_SIDE_BUTTON,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                icon = BitChordIcons.Shuffle,
                label = stringResource(R.string.shuffle),
                onClick = onShuffle,
                filled = false,
                enabled = canShuffle,
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
                enabled = canPlay,
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
                    if (isPinnedInLibrary) "Desafixar $pinTargetLabel da Biblioteca"
                    else if (onTogglePin == null) "Fixar $pinTargetLabel na Biblioteca (em breve)"
                    else "Fixar $pinTargetLabel na Biblioteca"
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
        val savedRating = normalizedCompactRating(rating)
        // Integrate ratings with release identity rather than creating a tiny
        // isolated third line between the artist and the playback controls.
        // If no score exists, the verified native editor remains reachable
        // from an outline (or the quality badge when one is shown).
        val inlineRating = savedRating != null || (onRate != null && quality == null)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HEADER_GUTTER),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("native-release-title"),
            )
            if (credit.isBlank() && inlineRating) {
                Spacer(Modifier.width(8.dp))
                NativeInlineReleaseRating(savedRating, onRate, palette)
            }
        }
        if (credit.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HEADER_GUTTER),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = credit,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Normal, fontSize = 19.sp,
                    ),
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                        .testTag("native-release-credit")
                        .let { m -> if (onCreditClick == null) m
                            else m.clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onCreditClick) },
                )
                if (inlineRating) {
                    Spacer(Modifier.width(8.dp))
                    NativeInlineReleaseRating(savedRating, onRate, palette)
                }
            }
        }
        // Audio quality retains its established path and independent evidence.
        // A saved personal score lives beside identity; never replace a score
        // with an inferred quality badge or vice versa.
        if (quality != null && (savedRating == null || onRate == null)) {
            Spacer(Modifier.height(8.dp))
            SourceQualityBadge(
                quality = quality,
                color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("native-album-source-quality")
                    .then(if (onRate == null) Modifier else Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Avaliar álbum", onClick = onRate)),
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



/** Reused by album and playlist identity rows; no independent rating shelf. */
@Composable
private fun NativeInlineReleaseRating(
    rating: Double?,
    onRate: (() -> Unit)?,
    palette: ArtworkPalette,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .testTag("native-album-rating")
            .then(if (onRate == null) Modifier else Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Avaliar álbum", onClick = onRate)),
        contentAlignment = Alignment.Center,
    ) {
        CompactRatingIndicator(
            value = rating,
            geometry = CompactRatingGeometry.STANDALONE,
            size = 32.dp,
            showUnrated = rating == null,
            neutralColor = palette.onBackground,
        )
    }
}
