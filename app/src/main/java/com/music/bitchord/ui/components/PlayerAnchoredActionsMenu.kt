package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.music.bitchord.ui.components.CompactRatingIndicator
import com.music.bitchord.ui.components.CompactRatingGeometry
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.ui.player.originalPortraitNowPlayingOverflowGlyph

/** Same drawable as the original Now Playing launcher; never substitute a generic More icon. */
internal fun playerAnchoredMenuHighlightGlyph() = originalPortraitNowPlayingOverflowGlyph()

/**
 * The player controls occupy the space directly behind the menu. Fading the
 * whole plate (including text) from 0 to 1 reveals those controls THROUGH its
 * rows, creating a transient double-exposure. Wait for measurement, then draw
 * an opaque plate and animate only its subtle anchored scale.
 */
internal fun playerAnchoredMenuPlateAlpha(measured: Boolean): Float =
    if (measured) 1f else 0f

internal fun playerAnchoredMenuPlateScale(progress: Float): Float =
    0.975f + 0.025f * progress.coerceIn(0f, 1f)

/** Both player overflow and rating use the shared contextual action plate. */
@Suppress("UNUSED_PARAMETER")
internal fun playerAnchoredMenuUsesSharedRatingPlate(highlightLauncher: Boolean): Boolean = true

/**
 * Draw the visible selected ⋯ over the exact launcher, even though the menu
 * owns a separate dialog window. Bounds and origin are window pixels.
 */
internal fun playerAnchoredMenuHighlightPosition(
    anchor: IntRect,
    overlayOrigin: IntOffset,
    overlaySize: IntSize,
    highlightSizePx: Int = 40,
): IntOffset {
    val diameter = highlightSizePx.coerceAtLeast(1)
    val x = ((anchor.left + anchor.right) / 2 - overlayOrigin.x - diameter / 2)
        .coerceIn(0, (overlaySize.width - diameter).coerceAtLeast(0))
    val y = ((anchor.top + anchor.bottom) / 2 - overlayOrigin.y - diameter / 2)
        .coerceIn(0, (overlaySize.height - diameter).coerceAtLeast(0))
    return IntOffset(x, y)
}

/**
 * Player actions are not a bottom sheet. The player occupies a separate M3
 * dialog window, so an ordinary Popup from the activity may land behind it.
 * A fresh, full-width dialog stays above Now Playing and aligns its native
 * plate to the actual overflow button bounds captured in that player window.
 *
 * The panel reuses the neutral contextual action plate. The player remains
 * visible only outside it, beneath the separate dialog's dimming scrim.
 * Its opaque panel prevents double-exposure during entrance scaling.
 */
@Composable
fun PlayerAnchoredActionsMenu(
    anchorBounds: IntRect?,
    artworkUrl: String?,
    onDismiss: () -> Unit,
    highlightLauncher: Boolean = true,
    highlightRatingLauncher: Boolean = false,
    ratingLauncherScore: Double? = null,
    maxWidth: Dp = 340.dp,
    content: @Composable () -> Unit,
) {
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var plateSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val margin = with(density) { 14.dp.roundToPx() }
    val reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current
    val entrance = remember { Animatable(0f) }
    // Respect the dialog's actual width, which can be much smaller than the
    // activity width in split-screen. A fixed 248dp minimum clipped menus.
    val availableWidth = if (rootSize.width > 0) with(density) {
        (rootSize.width - 2 * margin).coerceAtLeast(1).toDp()
    } else maxWidth
    val menuMaxWidth = minOf(maxWidth, availableWidth)
    // The Now Playing actions expand downward from the original launcher.
    // Bound their scrolling plate to space under it instead of flipping above.
    val maxPlateHeight = if (highlightLauncher && anchorBounds != null && rootSize.height > 0) {
        with(density) {
            (rootSize.height - (anchorBounds.bottom - rootOrigin.y + gap) - margin)
                .coerceAtLeast(1).toDp().coerceAtMost(570.dp)
        }
    } else 570.dp
    val measured = rootSize.width > 0 && rootSize.height > 0 &&
        plateSize.width > 0 && plateSize.height > 0
    LaunchedEffect(measured, reduceMotion) {
        if (!measured) return@LaunchedEffect
        if (reduceMotion) entrance.snapTo(1f)
        else entrance.animateTo(1f, tween(durationMillis = 170))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { rootSize = it }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    rootOrigin = IntOffset(pos.x.toInt(), pos.y.toInt())
                },
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.matchParentSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss))
            val anchor = anchorBounds ?: IntRect(
                rootSize.width - margin - with(density) { 44.dp.roundToPx() },
                rootOrigin.y + margin + with(density) { 72.dp.roundToPx() },
                rootSize.width - margin,
                rootOrigin.y + margin + with(density) { 116.dp.roundToPx() },
            )
            val place = Modifier.align(Alignment.TopStart)
                .offset {
                    anchoredFrostedMenuPosition(
                        anchor = anchor,
                        popup = plateSize,
                        overlay = rootSize,
                        overlayOrigin = rootOrigin,
                        gap = gap,
                        margin = margin,
                        preferBelow = highlightLauncher,
                    )
                }
            val plateModifier = place
                    .widthIn(min = minOf(248.dp, menuMaxWidth), max = menuMaxWidth)
                    .heightIn(max = maxPlateHeight)
                    .onSizeChanged { plateSize = it }
                    // No translation or spring tied to popup measurement: the
                    // dialog gets its own coordinate system and the old motion
                    // visibly jumped when the plate acquired its real size.
                    .graphicsLayer {
                        // The card is fully opaque whenever visible. Only its
                        // small scale transition animates; no player glyphs,
                        // seek bars or lyrics may show through action rows.
                        alpha = playerAnchoredMenuPlateAlpha(measured)
                        scaleX = playerAnchoredMenuPlateScale(entrance.value)
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(1f, 0f)
                    }
                    .clip(RoundedCornerShape(18.dp))
                    .testTag("player-anchored-actions-menu")
                    // Absorb taps on blank plate space without marking the
                    // entire editor clickable. A parent clickable merges the
                    // child's PaneTitle semantics and crashes Compose UI tests
                    // (and can break TalkBack). Child actions keep priority.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            // One shared neutral card for the player ⋯ and rating controls.
            // No separate album-art backdrop or second menu tint layer.
            NativeContextActionPlate(
                modifier = plateModifier,
                treatment = NativeActionCardTreatment.SOLID,
            ) {
                content()
            }

            // The native rating star lives behind this separate dialog window.
            // Repaint its exact circular launcher above the scrim while the
            // editor is open, just as the player's overflow menu does for ⋯.
            if (highlightRatingLauncher && !highlightLauncher && anchorBounds != null) {
                val highlightSize = 40.dp
                val highlightPx = with(density) { highlightSize.roundToPx() }
                Box(
                    modifier = Modifier.align(Alignment.TopStart)
                        .offset {
                            playerAnchoredMenuHighlightPosition(
                                anchor = anchorBounds,
                                overlayOrigin = rootOrigin,
                                overlaySize = rootSize,
                                highlightSizePx = highlightPx,
                            )
                        }
                        .size(highlightSize)
                        .border(0.75.dp, heldContextSelectionOutline(), CircleShape)
                        .testTag("player-rating-launcher-highlight")
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    CompactRatingIndicator(
                        value = ratingLauncherScore,
                        geometry = CompactRatingGeometry.CIRCULAR,
                        size = 34.dp,
                        showUnrated = true,
                        neutralColor = Color.White,
                    )
                }
            }

            // The activity's original puck is behind this dialog and blurred
            // with the player. A selected copy in the SAME screen-space bounds
            // makes the expanding menu visibly originate at its ⋯ launcher.
            if (highlightLauncher && anchorBounds != null) {
                val highlightSize = 40.dp
                val highlightPx = with(density) { highlightSize.roundToPx() }
                // The AM-DECOMP portrait vector ALREADY paints the circular
                // platter. No second Surface, grey fill, or alternative ⋯ icon.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            playerAnchoredMenuHighlightPosition(
                                anchor = anchorBounds,
                                overlayOrigin = rootOrigin,
                                overlaySize = rootSize,
                                highlightSizePx = highlightPx,
                            )
                        }
                        .size(highlightSize)
                        .border(0.75.dp, heldContextSelectionOutline(), CircleShape)
                        .testTag("player-overflow-highlight")
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = playerAnchoredMenuHighlightGlyph(),
                        contentDescription = "Fechar opções do player",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}
