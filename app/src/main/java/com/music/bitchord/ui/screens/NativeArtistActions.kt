package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons

/** Native artist star is context/capability-driven, not a legacy provider ID. */
internal enum class NativeArtistStarKind { FOLLOW, RATE, UNAVAILABLE }

internal fun nativeArtistStarKind(
    isSubscribed: Boolean?,
    canRate: Boolean,
): NativeArtistStarKind = when {
    isSubscribed != null -> NativeArtistStarKind.FOLLOW
    canRate -> NativeArtistStarKind.RATE
    else -> NativeArtistStarKind.UNAVAILABLE
}

private val ARTIST_PLAY_BUTTON = 70.dp
private const val ARTIST_PLAY_ICON_SCALE = 0.56f
private val ARTIST_SIDE_BUTTON = 52.dp
private val ARTIST_ACTION_GAP = 24.dp

/** Source-neutral native artist action row; never receives SubscriptionState. */
@Composable
internal fun ActionRow(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    bottomSpace: Dp = 22.dp,
    /** The artist header's subscribe state, or null where it isn't offered. */
    isSubscribed: Boolean? = null,
    onToggleSubscription: (() -> Unit)? = null,
    /** Only the canonical Library artist route can supply first-party rating. */
    onRateArtist: (() -> Unit)? = null,
    artistRated: Boolean = false,
    /** Apple's fill for the Play circle; null leaves it white. */
    playColor: Color? = null,
) {
    val starKind = nativeArtistStarKind(isSubscribed, onRateArtist != null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(ARTIST_ACTION_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = BitChordIcons.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
            onClick = onShuffle,
            haptic = Haptic.Resume,
            size = ARTIST_SIDE_BUTTON,
        )

        PlayCircle(
            onClick = onPlay,
            size = ARTIST_PLAY_BUTTON,
            iconScale = ARTIST_PLAY_ICON_SCALE,
            containerColor = playColor ?: Color.White,
        )

        if (starKind == NativeArtistStarKind.FOLLOW) {
            CircleIconButton(
                icon = if (isSubscribed == true) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (isSubscribed == true) R.string.unsubscribe else R.string.subscribe,
                ),
                onClick = { onToggleSubscription?.invoke() },
                haptic = if (isSubscribed == true) Haptic.ToggleOff else Haptic.ToggleOn,
                size = ARTIST_SIDE_BUTTON,
            )
        } else if (starKind == NativeArtistStarKind.RATE) {
            CircleIconButton(
                icon = if (artistRated) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = "Avaliar artista",
                onClick = { onRateArtist?.invoke() },
                haptic = Haptic.ToggleOn,
                size = ARTIST_SIDE_BUTTON,
            )
        } else {
            CircleIconButton(
                icon = Icons.Rounded.StarBorder,
                contentDescription = stringResource(R.string.subscribe),
                onClick = {},
                size = ARTIST_SIDE_BUTTON,
                enabled = false,
            )
        }
    }
    Spacer(Modifier.height(bottomSpace))
}

/**
 * The artist page's Play: a filled circle with the triangle punched out of it,
 * so the page shows through, as Apple's does.
 */
@Composable
private fun PlayCircle(
    onClick: () -> Unit,
    size: Dp,
    iconScale: Float,
    containerColor: Color,
) {
    val haptics = rememberHaptics()
    val description = stringResource(R.string.play)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                haptics.play(Haptic.Resume)
                onClick()
            }
            // Offscreen so the clear below cuts the circle drawn under it,
            // not whatever the page has behind.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawCircle(containerColor)
                val box = this.size.minDimension * iconScale
                val unit = box / 24f
                val origin = Offset((this.size.width - box) / 2f, (this.size.height - box) / 2f)
                val triangle = androidx.compose.ui.graphics.Path().apply {
                    moveTo(origin.x + 6.8f * unit, origin.y + 4.8f * unit)
                    lineTo(origin.x + 19.2f * unit, origin.y + 12f * unit)
                    lineTo(origin.x + 6.8f * unit, origin.y + 19.2f * unit)
                    close()
                }
                drawPath(triangle, Color.Black, blendMode = BlendMode.Clear)
                // The same round-joined pen the icon is drawn with.
                drawPath(
                    triangle,
                    Color.Black,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2f * unit,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                    blendMode = BlendMode.Clear,
                )
            }
            .semantics { contentDescription = description },
    )
}

