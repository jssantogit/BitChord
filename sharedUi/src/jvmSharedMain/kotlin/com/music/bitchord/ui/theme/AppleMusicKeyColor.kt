package com.music.bitchord.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * AM-DECOMP Apple Music Android 7.0.0 key_color_light/night declarations.
 * Use only for native selection and affirmative action affordances; a default
 * text/icon tint is neutral, and destructive actions keep semantic red.
 * Other colors/metrics are explicit BitChord adaptations, not proven Apple values.
 */
val AppleMusicLightKey = Color(0xFF0088FF)
val AppleMusicDarkKey = Color(0xFF0091FF)

fun appleMusicKeyColor(dark: Boolean): Color =
    if (dark) AppleMusicDarkKey else AppleMusicLightKey

/** Selected UI accents use Apple key blue; inactive marks keep neutral ink. */
fun appleSelectionAccentColor(selected: Boolean, dark: Boolean, neutral: Color): Color =
    if (selected) appleMusicKeyColor(dark) else neutral

@Composable
fun appleSelectionAccentColor(selected: Boolean, neutral: Color): Color =
    appleSelectionAccentColor(
        selected = selected,
        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        neutral = neutral,
    )


@Composable
fun appleMusicKeyColor(): Color =
    appleMusicKeyColor(dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
