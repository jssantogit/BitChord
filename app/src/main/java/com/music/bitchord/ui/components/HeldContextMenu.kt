package com.music.bitchord.ui.components

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/**
 * The held item stays in the original Compose tree. The only visual selection
 * treatment is a hole in the existing dimming scrim — no bitmap, ring, shadow,
 * extra background or altered corner radius. It cannot crop native cover/text.
 */
internal fun heldPreviewBackingColor(): Color = Color.Transparent

/** Kept for independent overflow/header controls; the held spotlight no
 * longer paints this outline, shadow or any other extra pixels. */
internal fun heldContextSelectionOutline(): Color = Color.White.copy(alpha = 0.36f)

internal fun heldSpotlightCutout(
    sourceBounds: androidx.compose.ui.geometry.Rect,
    overlayOrigin: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
): androidx.compose.ui.geometry.Rect? {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return null
    val left = (sourceBounds.left - overlayOrigin.x).coerceIn(0f, viewportWidth)
    val top = (sourceBounds.top - overlayOrigin.y).coerceIn(0f, viewportHeight)
    val right = (sourceBounds.right - overlayOrigin.x).coerceIn(0f, viewportWidth)
    val bottom = (sourceBounds.bottom - overlayOrigin.y).coerceIn(0f, viewportHeight)
    if (right <= left || bottom <= top) return null
    return androidx.compose.ui.geometry.Rect(left, top, right, bottom)
}

/** Reuse the Library filter's frosted action surface; keep a solid fallback for reduced blur. */
internal fun heldActionsUseHaze(reduceDynamicBlur: Boolean): Boolean = !reduceDynamicBlur

/**
 * The context menu anchored to an item held in place. The item stays in its
 * original list, the page behind it dims, and actions hang
 * off it.
 *
 * Nothing is copied or physically lifted. The source keeps drawing normally
 * while the dimming scrim excludes its exact bounds. Only the actions float
 * beside the item, preserving each native card's artwork and caption geometry.
 *
 * Only a hold opens this. The ⋮ keeps opening the bottom sheet — a tap on a
 * small button is a request for the full menu, while a hold on the item itself
 * is a gesture about *that item*, answered where the finger is.
 *
 * [item] and [held] come and go together: non-null while the menu is wanted,
 * null once anything has closed it. The last pair is kept here so the menu can
 * play its way back into the list after the caller has already let go of it.
 *
 * Both of the app's accessibility switches are honoured: "Reduce animation"
 * (or the system's animator scale at zero) turns the lift into a short fade
 * with nothing moving, and "Reduce dynamic blur" swaps the blurred page for a
 * plain dimming scrim, which is also the cost that setting exists to remove.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun <T : Any> HeldContextMenu(
    item: T?,
    held: HeldItem?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    /** Tapping the lifted item — open what it stands for. Null leaves it inert. */
    onPreviewClick: ((T) -> Unit)?,
    /** Actual app mini-player + tab chrome; never place actions behind them. */
    bottomChromeClearance: Dp = 100.dp,
    /** Parent already blurs its REAL composed page, not a separate Haze plate. */
    hostProvidesBlur: Boolean = false,
    /** The page's already contrast-corrected artwork elevation, when present. */
    plateTint: Color? = null,
    actions: @Composable (T) -> Unit,
) {
    val requested = if (item != null && held != null) item to held else null
    var last by remember { mutableStateOf<Pair<T, HeldItem>?>(null) }
    if (requested != null && requested != last) {
        SideEffect { last = requested }
    }
    val shown = requested ?: last ?: return
    val (menuItem, heldItem) = shown
    val open = requested != null
    val context = LocalContext.current
    val appReduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val systemAnimationsOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = appReduceAnimation || systemAnimationsOff
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            progress.animateTo(
                1f,
                if (reduceMotion) {
                    tween(durationMillis = 140, easing = LinearEasing)
                } else {
                    // Under-damped on purpose: the small overshoot is the "pop"
                    // of the item coming off the page.
                    spring(dampingRatio = 0.72f, stiffness = 420f)
                },
            )
        } else {
            progress.animateTo(
                0f,
                tween(durationMillis = if (reduceMotion) 110 else 230, easing = FastOutSlowInEasing),
            )
            last = null
        }
    }

    BackHandler(enabled = open, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Keep the actions neutral and legible regardless of cover colors.
    val plate = heldContextPlateColor(MaterialTheme.colorScheme.background, plateTint)
    val hairline = if (plate.luminance() < 0.5f)
        Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    // Preserve the existing dimming strength; only exclude the source item.
    val scrimColor = if (dark) Color.Black.copy(alpha = 0.30f)
        else Color.Black.copy(alpha = 0.18f)
    val menuShape = RoundedCornerShape(16.dp)
    // The source card does not move or get a highlight surface. A spotlight
    // in the backdrop leaves its actual artwork and typography undimmed.

    val insets = WindowInsets.safeDrawing
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOffset = it.positionInWindow() }
            // Once it is on its way out nothing on it answers any more — a
            // second tap on a row mid-exit would run its action twice.
            .then(if (open) Modifier else Modifier.pointerInput(Unit) { swallowAll() }),
        content = {
            // Dim everything except the real, still-composed selected item.
            // EvenOdd draws a cutout rather than painting a bright rectangle
            // or using a Clear blend that could erase the underlying page.
            Spacer(
                Modifier
                    .fillMaxSize()
                    .testTag("held-context-backdrop")
                    .drawBehind {
                        val cutout = heldSpotlightCutout(
                            sourceBounds = heldItem.bounds,
                            overlayOrigin = overlayOffset,
                            viewportWidth = size.width,
                            viewportHeight = size.height,
                        )
                        val mask = androidx.compose.ui.graphics.Path().apply {
                            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                            if (cutout != null) {
                                addRoundRect(
                                    androidx.compose.ui.geometry.RoundRect(
                                        cutout, CornerRadius(12.dp.toPx()),
                                    ),
                                )
                            }
                        }
                        drawPath(
                            path = mask,
                            color = scrimColor.copy(
                                alpha = scrimColor.alpha * progress.value.coerceIn(0f, 1f),
                            ),
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClick = onDismiss,
                    ),
            )
            // Transparent hit target at the same original bounds. The user's
            // real cover and caption remain painted by their source composable.
            Box(
                Modifier
                    .then(
                        if (onPreviewClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = open,
                                onClick = { onPreviewClick(menuItem) },
                            )
                        } else Modifier
                    )
                    .testTag("held-context-preview"),
            )
            Column(
                Modifier
                    // Taps between rows land on the menu, not the scrim under it.
                    .pointerInput(Unit) {}
                    .clip(menuShape)
                    // Same glass recipe as NativeContextActionPlate (Library filters).
                    // The neutral paint is a fallback until the Haze source is ready;
                    // blur stays opt-out for accessibility and low-power devices.
                    .background(plate)
                    .then(
                        if (heldActionsUseHaze(reduceDynamicBlur)) Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(plate.copy(alpha = 0.58f)),
                        ) else Modifier,
                    )
                    .border(0.5.dp, hairline, menuShape)
                    .verticalScroll(rememberScrollState())
                    .testTag("held-context-actions"),
            ) {
                actions(menuItem)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 12.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val top = insets.getTop(this) + 8.dp.roundToPx()
        val bottom = heldMenuVisibleBottom(
            viewportHeight = height,
            systemBottomInset = insets.getBottom(this) + 8.dp.roundToPx(),
            chromeReserve = bottomChromeClearance.roundToPx(),
        ).coerceAtLeast(top)

        val backdrop = measurables[0].measure(Constraints.fixed(width, height))

        // The transparent hit target follows the unmodified original item.
        // No bitmap, scale, shadow or crop is constructed for the preview.
        val itemBounds = heldItem.bounds.translate(-overlayOffset)
        val previewFrame = heldPreviewFixedFrame(heldItem.bounds, overlayOffset)
        val plateW = previewFrame.width
        val plateH = previewFrame.height
        val card = measurables[1].measure(Constraints.fixed(plateW, plateH))
        val menuWidth = (width - margin * 2).coerceAtMost(280.dp.roundToPx()).coerceAtLeast(0)
        val availableHeight = (bottom - top).coerceAtLeast(0)
        val visualW = itemBounds.width
        val visualH = itemBounds.height
        val visualLeft = itemBounds.left
        val visualTop = itemBounds.top
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = visualTop,
            sourceHeight = visualH,
            viewportTop = top,
            viewportBottom = bottom,
            gap = gap,
            requestedMenuHeight = heldMenuMaxHeight(
                availableHeight = availableHeight, minPreview = 0, gap = gap,
            ),
        )
        val menu = measurables[2].measure(
            Constraints(
                minWidth = menuWidth, maxWidth = menuWidth,
                maxHeight = placement.menuMaxHeight,
            ),
        )
        val visualCenterX = visualLeft + visualW / 2f
        val visualCenterY = visualTop + visualH / 2f
        val alignEnd = visualCenterX > width / 2f
        val menuX = (if (alignEnd) visualLeft + visualW - menuWidth else visualLeft).roundToInt()
            .coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))
        val menuY = if (placement.menuBelow) {
            (visualTop + visualH + gap).roundToInt()
        } else {
            (visualTop - gap - menu.height).roundToInt()
        }.coerceIn(top, (bottom - menu.height).coerceAtLeast(top))
        val cardX = previewFrame.x
        val cardY = previewFrame.y

        layout(width, height) {
            backdrop.place(0, 0)
            card.place(cardX, cardY)
            menu.placeWithLayer(menuX, menuY) {
                val p = progress.value
                alpha = ((p - 0.1f) / 0.9f).coerceIn(0f, 1f)
                if (!reduceMotion) {
                    // Grows out of the corner nearest the item, and travels
                    // with it.
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (alignEnd) 1f else 0f,
                        pivotFractionY = if (placement.menuBelow) 0f else 1f,
                    )
                    val scale = lerp(0.96f, 1f, p)
                    scaleX = scale
                    scaleY = scale
                    // The menu stays anchored to its measured position;
                    // translating it by a snapshot's displacement could
                    // overlap neighbouring cards and top-bar controls.
                }
            }
        }
    }
}

/** Transparent click-target geometry; never scale or copy the actual source item. */
internal data class HeldPreviewFixedFrame(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val scale: Float = 1f,
)

internal fun heldPreviewFixedFrame(
    sourceBounds: androidx.compose.ui.geometry.Rect,
    overlayOrigin: Offset,
): HeldPreviewFixedFrame = HeldPreviewFixedFrame(
    x = (sourceBounds.left - overlayOrigin.x).roundToInt(),
    y = (sourceBounds.top - overlayOrigin.y).roundToInt(),
    width = sourceBounds.width.roundToInt().coerceAtLeast(1),
    height = sourceBounds.height.roundToInt().coerceAtLeast(1),
)

/** Menu height is constrained to the available side; preview never moves. */
internal data class HeldAnchoredVerticalPlacement(
    val previewTop: Float,
    val menuTop: Int,
    val menuMaxHeight: Int,
    val menuBelow: Boolean,
)

internal fun heldMenuAnchoredVerticalPlacement(
    sourceTop: Float,
    sourceHeight: Float,
    viewportTop: Int,
    viewportBottom: Int,
    gap: Int,
    requestedMenuHeight: Int,
): HeldAnchoredVerticalPlacement {
    val belowSpace = (viewportBottom - (sourceTop + sourceHeight + gap)).toInt().coerceAtLeast(0)
    val aboveSpace = (sourceTop - gap - viewportTop).toInt().coerceAtLeast(0)
    val below = belowSpace >= requestedMenuHeight ||
        (belowSpace >= aboveSpace && belowSpace > 0)
    val available = if (below) belowSpace else aboveSpace
    val menuHeight = requestedMenuHeight.coerceAtLeast(1).coerceAtMost(available.coerceAtLeast(1))
    val y = if (below) (sourceTop + sourceHeight + gap).roundToInt()
        else (sourceTop - gap - menuHeight).roundToInt()
    return HeldAnchoredVerticalPlacement(
        previewTop = sourceTop,
        menuTop = y.coerceIn(viewportTop, (viewportBottom - menuHeight).coerceAtLeast(viewportTop)),
        menuMaxHeight = menuHeight,
        menuBelow = below,
    )
}

private suspend fun PointerInputScope.swallowAll() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * Held item and its action plate share the same neutral opaque material as
 * the Library filter. The caller's artwork tint is deliberately ignored:
 * dynamic colors behind white menu text were causing illegible green/purple
 * context menus and duplicated original-row content.
 */
@Suppress("UNUSED_PARAMETER")
internal fun heldContextPlateColor(themeBackground: Color, artworkElevation: Color?): Color =
    if (themeBackground.luminance() < 0.5f) Color(0xFF29292D) else Color(0xFFF5F5F7)
