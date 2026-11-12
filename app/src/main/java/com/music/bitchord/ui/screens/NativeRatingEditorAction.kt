package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.appleMusicKeyColor

/**
 * A native 52dp solid action for the minimal star-rating sheet.
 * Colors/geometry retain BitChord's prior concept, with no Material 3 Button
 * container, elevation or default disabled treatment. Saving callbacks live
 * with the canonical rating editor, not this presentation control.
 */
@Composable
internal fun NativeRatingEditorAction(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tag: String,
) {
    val neutral = if (darkTheme) Color.White else Color.Black
    val background = when {
        emphasized && enabled && !loading -> appleMusicKeyColor(darkTheme)
        emphasized && darkTheme -> Color(0xFF343437)
        emphasized -> Color(0xFFDEDEE1)
        darkTheme -> Color(0xFF2B2B2D)
        else -> Color(0xFFF0F0F2)
    }
    val ink = when {
        emphasized && enabled && !loading -> Color.White
        emphasized -> neutral.copy(alpha = 0.45f)
        enabled -> neutral
        else -> neutral.copy(alpha = 0.40f)
    }
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(background)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("native-rating-action-loading"),
                color = ink,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                color = ink,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}
