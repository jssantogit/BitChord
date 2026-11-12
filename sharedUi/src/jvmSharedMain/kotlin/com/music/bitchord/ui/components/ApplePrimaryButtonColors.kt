package com.music.bitchord.ui.components

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * Native blue affirmative action palette, shared by error, playlist, and
 * collaboration flows. Button remains a Compose accessibility primitive;
 * the color roles are Apple's documented key-color tokens plus neutral
 * disabled-state adaptations, not alleged pixel measurements from AM-DECOMP.
 */
@Composable
fun applePrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = appleMusicKeyColor(),
    contentColor = Color.White,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
