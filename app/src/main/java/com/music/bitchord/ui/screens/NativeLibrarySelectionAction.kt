package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * One multi-selection control for rows and cards in the remote Library.
 * Explicit 40dp geometry and selection semantics are BitChord adaptations;
 * no unverified Apple Music dimensions are attributed to these controls.
 */
@Composable
internal fun NativeLibrarySelectionAction(
    selected: Boolean,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String = "native-library-selection-action",
) {
    val neutral = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(neutral.copy(alpha = 0.065f))
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) PlaylistEditorIcons.Selected
                else PlaylistEditorIcons.Unselected,
            contentDescription = label,
            tint = (if (selected) appleMusicKeyColor() else neutral)
                .copy(alpha = if (enabled) 1f else 0.42f),
            modifier = Modifier.size(23.dp),
        )
    }
}
