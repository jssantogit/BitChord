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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.music.bitchord.ui.LocalReduceAnimations

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
    LaunchedEffect(expanded) { visible.targetState = expanded }
    val reduceMotion = LocalReduceAnimations.current
    val duration = if (reduceMotion) 0 else 170
    val exitDuration = if (reduceMotion) 0 else 130
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    if (expanded || visible.currentState || visible.targetState) {
        Popup(
            alignment = Alignment.BottomEnd,
            offset = IntOffset(0, gap),
            onDismissRequest = onDismissRequest,
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
                    Column(
                        modifier = Modifier.padding(vertical = 5.dp),
                        content = content,
                    )
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
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    val ink = color.copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .testTag("native-compact-menu-item")
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink,
                modifier = Modifier.size(22.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink)
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
