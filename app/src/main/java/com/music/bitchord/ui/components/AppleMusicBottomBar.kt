package com.music.bitchord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Screenshot-matched navigation experiment, deliberately separate from the
 * existing four-route FloatingBottomBar and iOS 26 GlassNavBar.
 *
 * The fifth *visual* slot is a non-interactive Radio preview. Its presence
 * must never change the route indices persisted by MainActivity.
 *
 * 62dp is the Android Apple Music resource height for the tab host; the visual
 * screenshot does not establish its exact runtime density or its background
 * blur parameters. Colors and spacing here follow the supplied screenshot.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppleMusicBottomBar(
    tabs: List<BottomTab>,
    selectedRouteIndex: Int,
    onRouteSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    require(tabs.size == APPLE_MUSIC_VISIBLE_TAB_COUNT) { "Expected five visual tabs" }
    val selectedVisualIndex = appleMusicVisualIndexForRoute(selectedRouteIndex)
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val shape = remember { RoundedCornerShape(percent = 50) }
    val surfaceColor = Color(0xE6313234)

    Row(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .fillMaxWidth()
            .height(62.dp)
            .clip(shape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(surfaceColor)
                } else {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(surfaceColor),
                    )
                },
            )
            .background(Color(0x300B0D10))
            .border(0.6.dp, Color.White.copy(alpha = 0.16f), shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEachIndexed { visualIndex, tab ->
            val route = appleMusicRouteForVisualIndex(visualIndex)
            val enabled = route != null
            val isSelected = enabled && selectedVisualIndex == visualIndex
            val targetTint = when {
                !enabled -> Color(0xFF8E8E93)
                isSelected -> Color(0xFF0091FF)
                else -> Color.White.copy(alpha = 0.96f)
            }
            val tint by animateColorAsState(
                targetValue = targetTint,
                animationSpec = if (reduceAnimation) snap() else tween(150),
                label = "appleMusicTabTint",
            )
            val interactionSource = remember { MutableInteractionSource() }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (enabled) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                if (!isSelected) haptics.play(Haptic.Select)
                                onRouteSelected(requireNotNull(route))
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) {
                        selected = isSelected
                        if (!enabled) disabled()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    color = tint,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
