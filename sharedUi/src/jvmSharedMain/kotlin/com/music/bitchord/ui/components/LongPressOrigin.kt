package com.music.bitchord.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * Provenance of a held item: its unchanged position in the real composition.
 *
 * We no longer capture or re-render the item into a bitmap. The context
 * overlay dims only what surrounds these bounds; the real card, artwork,
 * caption and native clip retain their original pixels and z-order.
 */
@Stable
class HeldItem internal constructor(
    val bounds: Rect,
) {
    /** Compatibility for dismiss callbacks predating the source-spotlight UI. */
    fun releaseLift() = Unit
}

/**
 * The most recently held native row/card. The caller consumes this synchronously
 * to distinguish a long press from the ordinary three-dot bottom-sheet action.
 */
object LongPressOrigin {
    private var held: HeldItem? = null
    private var recordedAt = 0L

    internal fun record(item: HeldItem) {
        held = item
        recordedAt = System.nanoTime()
    }

    fun consume(): HeldItem? {
        val item = held?.takeIf { System.nanoTime() - recordedAt < FRESH_NANOS }
        held = null
        return item
    }

    private const val FRESH_NANOS = 500_000_000L
}

/**
 * Long-press registration with no screenshot, graphics layer, shadow or
 * suppression of the source's original paint. This prevents a transient
 * white rectangle from being captured and stretched over carousel artwork.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressMenuClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    enabled: Boolean = true,
): Modifier {
    val capture = remember { HoldOriginCoordinates() }
    val longClick = onLongClick?.let { held ->
        longClick@{
            val coordinates = capture.coordinates?.takeIf { it.isAttached }
                ?: return@longClick
            val bounds = coordinates.boundsInWindow()
            if (!heldOriginFullyVisible(bounds, coordinates.size.width, coordinates.size.height)) {
                return@longClick
            }
            LongPressOrigin.record(HeldItem(bounds))
            held()
        }
    }
    // No pressed-ripple bitmap should ever obscure artwork or captions.
    val gesture = when {
        onClick != null && longClick != null -> Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = longClick,
        )
        onClick != null -> Modifier.combinedClickable(
            enabled = enabled, onClick = onClick, onLongClick = null,
        )
        longClick != null -> Modifier
            .pointerInput(enabled, longClick) {
                if (enabled) detectTapGestures(onLongPress = { longClick() })
            }
            .semantics {
                if (enabled) onLongClick(label = "Abrir ações") {
                    longClick()
                    true
                }
            }
        else -> Modifier
    }
    return this
        .onPlaced { capture.coordinates = it }
        .then(gesture)
}

private class HoldOriginCoordinates {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Ignore clipped or detached carousel entries. The spotlight should never
 * show an incomplete slice of a cover as though it were the whole selected item.
 */
internal fun heldOriginFullyVisible(
    bounds: Rect,
    sourceWidth: Int,
    sourceHeight: Int,
): Boolean = sourceWidth > 0 && sourceHeight > 0 &&
    bounds.width > 0f && bounds.height > 0f &&
    kotlin.math.abs(bounds.width - sourceWidth) <= 1f &&
    kotlin.math.abs(bounds.height - sourceHeight) <= 1f
