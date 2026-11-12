package com.music.bitchord.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * Shared Settings toggle palette. AM-DECOMP verifies only the key blue color,
 * not the measured switch design. Keep Material's semantics, gestures and
 * accessibility while replacing the generic selected colors with BitChord's
 * native theme roles in every Settings subsection.
 */
@Composable
fun appleSettingsSwitchColors(): SwitchColors {
    val checkedBlue = appleMusicKeyColor()
    return SwitchDefaults.colors(
        checkedTrackColor = checkedBlue,
        checkedBorderColor = checkedBlue,
        checkedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
