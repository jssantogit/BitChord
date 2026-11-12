package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * Secondary search action using BitChord's Apple-derived neutral surfaces.
 * Unlike the Material 3 OutlinedButton, the corner, border, fill and height
 * are explicit BitChord adaptations; am-descomp has no proven metrics for this CTA.
 * Callers own pagination, search, and playback — this component never fetches.
 */
@Composable
fun NativeSearchSecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val active = enabled && !loading
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(ink.copy(alpha = 0.055f))
            .border(1.dp, ink.copy(alpha = 0.13f), shape)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 15.dp, vertical = 9.dp)
            .testTag("native-search-secondary-action")
            .semantics { if (loading) stateDescription = "Carregando" },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                strokeWidth = 2.dp,
                color = appleMusicKeyColor(),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = ink.copy(alpha = if (enabled) 1f else 0.42f),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = if (enabled) 1f else 0.42f),
        )
    }
}
