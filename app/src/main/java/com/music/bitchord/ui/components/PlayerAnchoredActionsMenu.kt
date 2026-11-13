package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * Player actions are not a bottom sheet. The player occupies a separate M3
 * dialog window, so an ordinary Popup from the activity may land behind it.
 * A fresh, full-width dialog stays above Now Playing and aligns its native
 * plate to the actual overflow button bounds captured in that player window.
 *
 * Both layers use the existing artwork palette. The plate's background
 * contains the same wide, artwork-derived blur used in the old player sheet;
 * the player itself is separately blurred by its host while this dialog is up.
 */
@Composable
fun PlayerAnchoredActionsMenu(
    anchorBounds: IntRect?,
    artworkUrl: String?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = rememberArtworkPalette(imageUrl = artworkUrl)
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var plateSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val margin = with(density) { 14.dp.roundToPx() }
    val entrance = rememberFrostedMenuEntranceModifier(reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { rootSize = it }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    rootOrigin = IntOffset(pos.x.toInt(), pos.y.toInt())
                },
            contentAlignment = Alignment.TopStart,
        ) {
            Box(Modifier.matchParentSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss))
            val anchor = anchorBounds ?: IntRect(
                rootSize.width - margin - with(density) { 44.dp.roundToPx() },
                rootOrigin.y + margin + with(density) { 72.dp.roundToPx() },
                rootSize.width - margin,
                rootOrigin.y + margin + with(density) { 116.dp.roundToPx() },
            )
            val place = Modifier.align(Alignment.TopStart)
                .offset {
                    anchoredFrostedMenuPosition(
                        anchor = anchor,
                        popup = plateSize,
                        overlay = rootSize,
                        overlayOrigin = rootOrigin,
                        gap = gap,
                        margin = margin,
                    )
                }
            Surface(
                modifier = place
                    .widthIn(min = 248.dp, max = 340.dp)
                    .heightIn(max = 570.dp)
                    .onSizeChanged { plateSize = it }
                    .then(entrance)
                    .clip(RoundedCornerShape(18.dp))
                    .testTag("player-anchored-actions-menu")
                    .clickable(onClick = {}),
                color = palette.elevated,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 22.dp,
            ) {
                Box {
                    ArtworkBackdrop(
                        palette = palette,
                        imageUrl = artworkUrl,
                        modifier = Modifier.matchParentSize(),
                        washFraction = 0.78f,
                    )
                    Box(Modifier.background(Color.Black.copy(alpha = 0.25f))) {
                        content()
                    }
                }
            }
        }
    }
}
