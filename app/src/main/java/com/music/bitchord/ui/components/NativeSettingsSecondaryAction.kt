package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.theme.appleMusicKeyColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Neutral secondary Settings action used when a full-width row is appropriate.
 * This visual is a BitChord adaptation: the exact Apple Music geometry is
 * undocumented in am-descomp. Keep selection and destructive confirmations
 * outside this component.
 */
@Composable
internal fun NativeSettingsSecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(ink.copy(alpha = if (enabled) 0.055f else 0.025f))
            .border(1.dp, ink.copy(alpha = if (enabled) 0.13f else 0.06f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("native-settings-secondary-action"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = if (enabled) 1f else 0.40f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Solid 50dp key-blue Settings action; credential and token handling remain
 * with the caller. Geometry is a BitChord adaptation of the native palette. */
@Composable
internal fun NativeSettingsPrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(
                if (enabled) appleMusicKeyColor()
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag("native-settings-primary-action"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
