package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.AppleFloatingControlMetrics
import com.music.bitchord.ui.components.appleSecondaryActionSurface
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/** Shared first-party detail buttons. No provider DTO or playlist identity. */
internal const val LIGHT_FILL_ALPHA = AppleFloatingControlMetrics.secondaryFillAlpha
internal const val TOP_RELEASE_EDGE_ALPHA = AppleFloatingControlMetrics.secondaryEdgeAlpha
internal const val DISABLED_ACTION_ALPHA = AppleFloatingControlMetrics.disabledAlpha

@Composable
internal fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = AppleFloatingControlMetrics.labeledPillHeight,
) {
    // Resume rather than a flat tap: both start a queue, and the rising pair
    // says so.
    val haptics = rememberHaptics()
    val content = if (filled) Color.Black else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ACTION_ALPHA)
            .height(size)
            .clip(CircleShape)
            .then(
                if (filled) {
                    Modifier.background(Color.White)
                } else {
                    Modifier.appleSecondaryActionSurface()
                },
            )
            .then(
                if (enabled) Modifier.clickable {
                    haptics.play(Haptic.Resume)
                    onClick()
                } else Modifier.semantics(mergeDescendants = true) {
                    disabled()
                    role = Role.Button
                },
            )
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    haptic: Haptic = Haptic.Tap,
    size: Dp = AppleFloatingControlMetrics.detailDiameter,
    /**
     * False greys the button out and stops it taking taps, for an action the
     * page offers but this user can't take — a guest's Add or Subscribe.
     * Dimmed rather than dropped, so the row keeps its shape.
     */
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    customIcon: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    // Keep the action label on the clickable parent, not an inert child node.
    val actionLabel = contentDescription
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ACTION_ALPHA)
            .clip(CircleShape)
            // One shared material for detailed icon and labeled actions.
            .appleSecondaryActionSurface()
            .then(
                if (customIcon != null) Modifier.semantics(mergeDescendants = true) {
                    contentDescription = actionLabel
                } else Modifier
            )
            .then(
                if (enabled) Modifier.clickable {
                    haptics.play(haptic)
                    onClick()
                } else Modifier.semantics(mergeDescendants = true) {
                    disabled()
                    role = Role.Button
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (customIcon != null) {
            customIcon()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}

