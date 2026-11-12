package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.music.bitchord.ui.LocalReduceAnimations

/** Popups must not cover the control that opened them.
 *
 * Prefer directly below with the configured gap; if that would clip the
 * content, prefer above. The fallback for menus taller than either side
 * pins content to the top, keeping all possible options within the window.
 * End alignment mirrors in RTL and is clamped at both horizontal edges.
 */
internal fun compactOverflowPopupPosition(
    anchorBounds: IntRect,
    popupSize: IntSize,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    gapPx: Int,
): IntOffset {
    val gap = gapPx.coerceAtLeast(0)
    val x = when (layoutDirection) {
        LayoutDirection.Ltr -> anchorBounds.right - popupSize.width
        LayoutDirection.Rtl -> anchorBounds.left
    }.coerceIn(0, (windowSize.width - popupSize.width).coerceAtLeast(0))
    val below = anchorBounds.bottom + gap
    val above = anchorBounds.top - popupSize.height - gap
    val y = when {
        below + popupSize.height <= windowSize.height -> below
        above >= 0 -> above
        else -> 0
    }.coerceIn(0, (windowSize.height - popupSize.height).coerceAtLeast(0))
    return IntOffset(x, y)
}

/** Keep exiting menu rows visible for the fade, but no longer actionable. */
private val LocalNativeCompactMenuActionsEnabled = compositionLocalOf { true }

/** One tap consumes the whole popup's action gate before its dismiss animation. */
private class NativeCompactMenuActionGate {
    var consumed by mutableStateOf(false)
        private set

    fun consumeOnce(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    fun close() { consumed = true }
    fun reset() { consumed = false }
}

private val LocalNativeCompactMenuActionGate =
    compositionLocalOf<NativeCompactMenuActionGate?> { null }

/**
 * One compact popup surface for Home/Explore and the playlist editor.
 *
 * The am-descomp reference confirms a dark rounded popup near the upper-right
 * controls, but does NOT establish its placement, dimensions or interpolator.
 * This is an explicit BitChord adaptation: a 12dp anchor gap, readable rows
 * and a restrained top-right fade/scale (disabled for reduced motion).
 *
 * Keep the Popup composed through the exit transition so it can actually
 * animate when the caller dismisses it (unlike removing a DropdownMenu in one
 * frame). Outside tap and Back remain owned by the Popup.
 */
@Composable
fun NativeCompactOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    // Keep the same gate through the exit fade: recreating it at dismiss
    // re-enabled rows held by the old Popup subcomposition.
    val actionGate = remember { NativeCompactMenuActionGate() }
    LaunchedEffect(expanded) {
        if (expanded) actionGate.reset() else actionGate.close()
        visible.targetState = expanded
    }
    val reduceMotion = LocalReduceAnimations.current
    val duration = if (reduceMotion) 0 else 170
    val exitDuration = if (reduceMotion) 0 else 130
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val popupPositionProvider = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = compactOverflowPopupPosition(
                anchorBounds, popupContentSize, windowSize, layoutDirection, gap,
            )
        }
    }
    if (expanded || visible.currentState || visible.targetState) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = {
                actionGate.close()
                onDismissRequest()
            },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true,
                dismissOnClickOutside = true, clippingEnabled = true),
        ) {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(tween(duration)) + scaleIn(
                    initialScale = 0.95f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(duration, easing = FastOutSlowInEasing),
                ),
                exit = fadeOut(tween(exitDuration)) + scaleOut(
                    targetScale = 0.97f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(exitDuration, easing = FastOutSlowInEasing),
                ),
            ) {
                Surface(
                    modifier = modifier.widthIn(min = 208.dp, max = 320.dp)
                        .testTag("native-compact-overflow"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 10.dp,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    CompositionLocalProvider(
                        LocalNativeCompactMenuActionsEnabled provides expanded,
                        LocalNativeCompactMenuActionGate provides actionGate,
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 5.dp),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NativeCompactMenuItem(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    selected: Boolean? = null,
) {
    val actionGate = LocalNativeCompactMenuActionGate.current
    val actionEnabled = enabled && LocalNativeCompactMenuActionsEnabled.current &&
        (actionGate?.consumed != true)
    val color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    val ink = color.copy(alpha = if (enabled) 1f else 0.4f)
    val activate = { if (actionGate?.consumeOnce() != false) onClick() }
    val actionModifier = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = actionEnabled,
            role = Role.RadioButton, onClick = activate)
    } else {
        Modifier.clickable(enabled = actionEnabled, onClick = activate)
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(actionModifier)
            .semantics {
                testTag = "native-compact-menu-item"
                // The gate is read at semantics evaluation time, not only at
                // recomposition: an exiting Popup may retain its old lambda.
                if (!actionEnabled || actionGate?.consumed == true) disabled()
                if (selected != null) this.selected = selected
            }
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink,
                modifier = Modifier.size(22.dp))
        }
        Text(label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge, color = ink)
        if (selected == true) {
            Icon(PlaylistEditorIcons.Check, contentDescription = null,
                tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun NativeCompactMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
