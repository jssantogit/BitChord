package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * Shared native inline action for app text controls.
 *
 * The 44dp target and radius are BitChord adaptations. am-descomp does not
 * establish Apple Music's exact geometry for these product-specific actions.
 * Red is reserved for destructive actions; subdued remains neutral.
 */
@Composable
internal fun NativeInlineTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subdued: Boolean = false,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    tag: String = "native-inline-text-action",
) {
    val ink = when {
        destructive -> MaterialTheme.colorScheme.error
        subdued -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> appleMusicKeyColor()
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = ink.copy(alpha = if (enabled) 1f else 0.40f),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = if (enabled) 1f else 0.40f),
        )
    }
}
