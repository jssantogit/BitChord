package com.music.bitchord.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.play_next
import com.music.bitchord.sharedui.resources.queue
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** Provider-neutral native track geometry and gestures.
 * All source identity stays with the caller; no Song/YouTube dependencies. */

/**
 * Exactly the same swipe-to-queue gesture for Song and typed Recording rows.
 * Only the row's presentation and queue callback are supplied by each source.
 * The 45% threshold, haptics, reveal and snap-back remain source-independent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeDetailTrackSwipeContainer(
    onSwipeToQueue: (() -> Unit)?,
    rowBackground: Color,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val haptics = rememberHaptics()
    val swipeStateHolder = remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    var boxWidth by remember { mutableFloatStateOf(0f) }

    val currentOnSwipeToQueue by rememberUpdatedState(onSwipeToQueue)

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && currentOnSwipeToQueue != null) {
                val offset = try { swipeStateHolder.value?.requireOffset() ?: 0f } catch (e: Exception) { 0f }
                if (abs(offset) >= boxWidth * 0.45f) {
                    haptics.play(Haptic.Select)
                    currentOnSwipeToQueue?.invoke()
                }
            }
            false // snap back without ever dismissing the recording
        },
        positionalThreshold = { distance -> distance * 0.5f },
    )
    swipeStateHolder.value = swipeState

    if (onSwipeToQueue == null) {
        content(modifier)
        return
    }

    LaunchedEffect(swipeState, boxWidth) {
        if (boxWidth <= 0f) return@LaunchedEffect
        val armAt = boxWidth * 0.45f
        var armed = false
        snapshotFlow { try { swipeState.requireOffset() } catch (e: Exception) { 0f } }
            .collect { offset ->
                val travelled = abs(offset)
                when {
                    !armed && travelled >= armAt -> {
                        armed = true
                        haptics.play(Haptic.Tick)
                    }
                    armed && travelled < armAt * 0.8f -> armed = false
                }
            }
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier.onSizeChanged { boxWidth = it.width.toFloat() },
        backgroundContent = { QueueSwipeBackground(swipeState) },
    ) {
        content(Modifier.background(rowBackground))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSwipeBackground(swipeState: SwipeToDismissBoxState) {
    val playerSetting = AppUi.hostOrNull?.swipeToPlayNext
    val playNext = playerSetting?.collectAsStateWithLifecycle()?.value ?: false
    Row(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val offset = try { swipeState.requireOffset() } catch (e: Exception) { 0f }
                if (offset > 0f) {
                    clipRect(left = 0f, top = 0f, right = offset, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                } else if (offset < 0f) {
                    clipRect(left = size.width + offset, top = 0f, right = size.width, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = PAGE_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        QueueSwipeLabel(playNext)
        QueueSwipeLabel(playNext)
    }
}

@Composable
private fun QueueSwipeLabel(playNext: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (playNext) Icons.Rounded.PlaylistPlay else Icons.Rounded.PlaylistAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (playNext) Res.string.play_next else Res.string.queue),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Native artwork frame for playable playlist/song rows across all providers.
 * Source-specific artwork is an injected slot; dimensions and playback bars
 * are identical, without manufacturing a legacy Song from a RecordingId.
 */
@Composable
fun NativeDetailTrackArtwork(
    showPlayingBars: Boolean = false,
    artwork: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.size(52.dp).testTag("native-detail-track-artwork")) {
        artwork()
        if (showPlayingBars) {
            SearchPlayingBars(Modifier.align(Alignment.Center))
        }
    }
}

/** Number glyph and its 52dp frame used by both SongRow and the
 * canonical album's numbered release rows. The identical inner placement is
 * important: the number and title must not shift between sources. */
@Composable
fun NativeDetailTrackNumber(
    number: Int,
    color: Color,
    showPlayingBars: Boolean = false,
) {
    Box(
        Modifier.width(TRACK_NUMBER_WIDTH).height(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (showPlayingBars) {
            SearchPlayingBars()
        } else {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = color,
                maxLines = 1,
            )
        }
    }
}

/**
 * The same physical row surface used by native SongRow and canonical RecordingId rows.
 * Media identity belongs to the caller; this shared component owns touch, gutters,
 * spacing and alignment, not playback resolution or queue conversion.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NativeDetailTrackRowFrame(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    background: Color = Color.Transparent,
    opacity: Float = 1f,
    horizontalInset: Dp = PAGE_GUTTER,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(opacity)
            .background(background)
            .then(if (onClick != null) {
                Modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
                    .contextClick(onMore ?: onLongPress)
            } else Modifier)
            .padding(horizontal = horizontalInset, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One trailing rating geometry for legacy Song and canonical Recording rows.
 * Rating provenance remains in the caller, not in this presentation slot.
 */
@Composable
fun NativeDetailTrackRatingSlot(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 8.dp).testTag("native-detail-rating-slot"),
        horizontalAlignment = Alignment.End,
        content = content,
    )
}

/** SongRow and canonical rows share title/subtitle weight and baseline spacing. */
@Composable
fun RowScope.NativeTrackRowText(
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
) {
    Column(Modifier.weight(1f)) {
        title()
        Spacer(Modifier.height(2.dp))
        subtitle()
    }
}

