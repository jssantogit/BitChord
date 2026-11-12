package com.music.bitchord.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * Settings continuous controls use the verified Apple key-blue role.
 * Slider geometry remains Compose's accessible primitive: AM-DECOMP has
 * not verified slider track/thumb dimensions for these BitChord settings.
 */
@Composable
fun appleSettingsSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = appleMusicKeyColor(),
    activeTrackColor = appleMusicKeyColor(),
    inactiveTrackColor = MaterialTheme.colorScheme.outline,
)
