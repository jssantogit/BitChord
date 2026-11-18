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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.appleMusicKeyColor

/** The rating editor uses filled, rounded cancel/confirm capsules. */
internal data class RatingEditorActionColors(val background: Color, val ink: Color)

internal fun ratingEditorActionHeightDp(): Int = 44
internal fun ratingEditorActionCornerRadiusDp(): Int = 999

internal fun ratingEditorActionColors(
    darkTheme: Boolean,
    emphasized: Boolean,
    enabled: Boolean,
    loading: Boolean,
): RatingEditorActionColors {
    val neutralSurface = if (darkTheme) Color(0xFF36363B) else Color(0xFFE5E5E8)
    val background = if (emphasized) appleMusicKeyColor(darkTheme) else neutralSurface
    val ink = if (emphasized || darkTheme) Color.White else Color.Black
    return RatingEditorActionColors(
        background = background.copy(alpha = if (enabled || loading) 1f else 0.46f),
        ink = ink.copy(alpha = if (enabled || loading) 1f else 0.55f),
    )
}

/**
 * Screenshot-matched neutral cancel and blue affirmative action.
 * The background remains the same frosted neutral plate as the library cards.
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
    val colors = ratingEditorActionColors(darkTheme, emphasized, enabled, loading)
    Box(
        modifier = modifier
            .height(ratingEditorActionHeightDp().dp)
            .clip(RoundedCornerShape(ratingEditorActionCornerRadiusDp().dp))
            .background(colors.background)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("native-rating-action-loading"),
                color = colors.ink,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                color = colors.ink,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}
