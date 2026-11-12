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
import com.music.bitchord.ui.player.AppleQueueVectors
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.play_next
import com.music.bitchord.sharedui.resources.queue
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import com.music.bitchord.sharedui.resources.more
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
 * Complete provider-independent track-row composition. Source adapters supply
 * artwork, text, rating and capability-specific actions. This layer owns the
 * exact same touch region, gutters, text width and rating/action order.
 * Neither Song/videoId nor provider-specific RecordingId belong here.
 */
@Composable
fun NativeDetailTrackRowLayout(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    background: Color = Color.Transparent,
    opacity: Float = 1f,
    horizontalInset: Dp = PAGE_GUTTER,
    textGap: Dp = 14.dp,
    leading: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
    afterText: @Composable RowScope.() -> Unit = {},
    rating: @Composable ColumnScope.() -> Unit,
    showRatingSlot: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    NativeDetailTrackRowFrame(
        onClick = onClick,
        modifier = modifier,
        onLongPress = onLongPress,
        onMore = onMore,
        background = background,
        opacity = opacity,
        horizontalInset = horizontalInset,
    ) {
        leading()
        Spacer(Modifier.width(textGap))
        NativeTrackRowText(title = title, subtitle = subtitle)
        afterText()
        if (showRatingSlot) NativeDetailTrackRatingSlot(content = rating)
        actions()
    }
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


/** Shared plain overflow glyph across toolbars and contextual row menus. */
internal fun nativeRowMoreGlyph() = AppleQueueVectors.MorePlain

/**
 * The ⋮ at the end of a row. Its circle is a touch target nobody sees, so it
 * hangs [ROW_MORE_OUTSET] into the page gutter: the dots end at the row's
 * content edge, mirroring the artwork on the left, rather than a whole
 * button's width short of it — and the row's text gets that width back.
 */
@Composable
fun RowMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 20.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .hangIntoGutter(ROW_MORE_OUTSET)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            nativeRowMoreGlyph(),
            contentDescription = contentDescription ?: stringResource(Res.string.more),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** How far a row's [RowMoreButton] reaches past the content edge into the gutter. */
val ROW_MORE_OUTSET = 14.dp

/**
 * Lays a trailing control out [outset] narrower than it draws, so it overhangs
 * its row's end edge by that much: for buttons whose glyph is far smaller than
 * their touch target, which would otherwise sit visibly inset from the edge.
 */
fun Modifier.hangIntoGutter(outset: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = (placeable.width - outset.roundToPx()).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** The accent a current song's title takes in lists that mark it with [SearchPlayingBars]. */
val PlayingAccent = Color(0xFFFB4A62)

@Composable
fun SearchPlayingBars(modifier: Modifier = Modifier) {
    val heights = listOf(0.38f, 0.78f, 0.52f).mapIndexed { index, minimum ->
        val transition = rememberInfiniteTransition(label = "search playing bar $index")
        val height by transition.animateFloat(
            initialValue = minimum,
            targetValue = 1f - (index * 0.12f),
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 520 + index * 130
                    minimum at 0
                    1f at (260 + index * 50)
                    minimum at durationMillis
                },
                repeatMode = RepeatMode.Reverse,
            ),
            label = "search bar height $index",
        )
        height
    }
    // The plate is a fixed square so only the bars move, never the box around them.
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.52f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            heights.forEach { height ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp * height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White),
                )
            }
        }
    }
}

