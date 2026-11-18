package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Standard native detail puck; no standalone Material 3 IconButton geometry. */
@Composable
internal fun PlaylistSuggestionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(Modifier.size(40.dp).testTag("playlist-suggestion-action")) {
        CircleIconButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            size = 40.dp,
            enabled = enabled,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
