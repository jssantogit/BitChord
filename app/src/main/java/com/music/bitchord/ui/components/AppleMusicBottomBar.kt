package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.PlayerDock
import com.music.bitchord.ui.player.rememberPlaybackArtworkImageModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * PR #209 experiment: Apple Music 7.0 night-style floating chrome.
 *
 * The screenshot's two independent, round pills are intentionally used for
 * both Haze and liquid-glass settings. This does NOT enable a fifth route:
 * Radio occupies a real visual slot but is disabled until a Radio screen exists.
 * The four actual destination IDs and their restored states remain unchanged.
 *
 * Geometry is from the documented Android resources (62dp navigation, 48dp
 * miniplayer), not a pixel-to-dp conversion of the cropped reference image.
 */
private val AppleNightSurface = Color(0xFF2B2B2D)
private val AppleBlue = Color(0xFF0091FF)
private val AppleWhite = Color(0xFFF9F9FB)
private val AppleSecondary = Color(0xFFB6B6BB)
private val ApplePillShape = RoundedCornerShape(percent = 50)

/**
 * AM-DECOMP contract: 62dp host, 30dp icons and 48dp default mini-player are
 * static XML tokens. The screenshots suggest larger labels, artwork and
 * transport glyphs than the first smoke; those values are visual calibration,
 * not claimed to be measurements or a proven w675dp configuration.
 */
internal object AppleMusicChromeMetrics {
    val navigationHeight = 62.dp
    val navigationIconSize = 30.dp
    val navigationLabelSize = 13.sp

    val miniPlayerHeight = 48.dp
    val artworkSize = 34.dp
    val controlTouchSize = 40.dp
    val playGlyphSize = 32.dp
    val nextGlyphSize = 32.dp
    val titleSize = 15.sp
    val artistSize = 15.sp
}

/** Maps the existing four real destinations to five *visible* tab positions. */
internal fun appleMusicVisualIndexForDestination(destination: Int): Int = when (destination) {
    0 -> 0 // Home
    1 -> 1 // Explore
    2 -> 3 // Library
    3 -> 4 // Search
    else -> 0
}

/** The middle Radio entry is intentionally not navigable. */
internal fun appleMusicDestinationForVisualIndex(visibleIndex: Int): Int? = when (visibleIndex) {
    0 -> 0
    1 -> 1
    3 -> 2
    4 -> 3
    else -> null
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun Modifier.appleMusicChrome(hazeState: HazeState): Modifier {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    return clip(ApplePillShape)
        .then(
            if (reduceDynamicBlur) {
                Modifier.background(AppleNightSurface)
            } else {
                Modifier.optimizedHazeEffect(
                    state = hazeState,
                    style = HazeMaterials.regular(AppleNightSurface),
                )
            },
        )
        .border(0.5.dp, Color.White.copy(alpha = 0.12f), ApplePillShape)
}

@Composable
fun AppleMusicBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    // Keep the original Home/Explore/Library/Search list and all route indices.
    require(tabs.size == 4) { "Apple Music experiment requires four real routes" }
    val radioLabel = stringResource(R.string.apple_music_tab_radio)
    val radioAccessibilityLabel = stringResource(R.string.apple_music_tab_radio_unavailable)
    val visualTabs = remember(tabs, radioLabel) {
        listOf(tabs[0], tabs[1], BottomTab(radioLabel, AppleMusicRadioWaves), tabs[2], tabs[3])
    }
    val selectedVisibleIndex = appleMusicVisualIndexForDestination(selectedIndex)
    val haptics = rememberHaptics()

    Row(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = 10.dp)
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .height(AppleMusicChromeMetrics.navigationHeight)
            .appleMusicChrome(hazeState)
            .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visualTabs.forEachIndexed { visibleIndex, tab ->
            val destination = appleMusicDestinationForVisualIndex(visibleIndex)
            val enabled = destination != null
            val selected = selectedVisibleIndex == visibleIndex
            val tint = when {
                !enabled -> AppleWhite.copy(alpha = 0.34f)
                selected -> AppleBlue
                else -> AppleWhite
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (destination != null) {
                            if (!selected) haptics.play(Haptic.Select)
                            onTabSelected(destination)
                        }
                    }
                    .then(
                        if (!enabled) Modifier.semantics {
                            disabled()
                            contentDescription = radioAccessibilityLabel
                        } else Modifier,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(AppleMusicChromeMetrics.navigationIconSize),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    fontSize = AppleMusicChromeMetrics.navigationLabelSize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The reference's separate 48dp now-playing pill. The existing gesture and
 * dock modifiers retain swipe-to-expand/skip and the player's artwork landing
 * without altering playback, queue, or player-sheet state.
 */
@Composable
fun AppleMusicMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
    dock: PlayerDock? = null,
    pull: MiniPlayerPull? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .padding(horizontal = 10.dp)
            .miniPlayerGestures(
                pull = pull,
                onNext = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                onPrevious = {
                    haptics.play(Haptic.SkipPrevious)
                    onPrevious()
                },
                locked = controlsLocked,
                onBlocked = onBlockedControl,
            )
            .fillMaxWidth()
            .height(AppleMusicChromeMetrics.miniPlayerHeight)
            .appleMusicChrome(hazeState)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(AppleMusicChromeMetrics.miniPlayerHeight).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artCorner = RoundedCornerShape(6.dp)
            AsyncImage(
                model = rememberPlaybackArtworkImageModel(song, ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(AppleMusicChromeMetrics.artworkSize)
                    .playerDockArt(dock, 6.dp)
                    .clip(artCorner)
                    .thumbnailBorder(artCorner)
                    .background(AppleNightSurface),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                ExplicitSongTitle(
                    song = song,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = AppleMusicChromeMetrics.titleSize,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = AppleWhite,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = AppleMusicChromeMetrics.artistSize,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = AppleSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                Box(Modifier.size(AppleMusicChromeMetrics.controlTouchSize), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = AppleWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                        onPlayPause()
                    },
                    modifier = Modifier.size(AppleMusicChromeMetrics.controlTouchSize),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = AppleWhite,
                        modifier = Modifier.size(AppleMusicChromeMetrics.playGlyphSize),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                enabled = !controlsLocked,
                modifier = Modifier.size(AppleMusicChromeMetrics.controlTouchSize),
            ) {
                MiniPlayerNextGlyph(
                    tint = AppleWhite.copy(alpha = if (controlsLocked) 0.34f else 1f),
                    glyphSize = AppleMusicChromeMetrics.nextGlyphSize,
                )
            }
        }
    }
}

/** Original wave glyph with the same silhouette family as the supplied image. */
private val AppleMusicRadioWaves: ImageVector by lazy {
    val paint = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "apple_music_radio_future",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(stroke = paint, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(8.5f, 8.1f)
            curveTo(5.9f, 10.3f, 5.9f, 13.7f, 8.5f, 15.9f)
            moveTo(15.5f, 8.1f)
            curveTo(18.1f, 10.3f, 18.1f, 13.7f, 15.5f, 15.9f)
            moveTo(6.1f, 5.5f)
            curveTo(1.4f, 9.0f, 1.4f, 15.0f, 6.1f, 18.5f)
            moveTo(17.9f, 5.5f)
            curveTo(22.6f, 9.0f, 22.6f, 15.0f, 17.9f, 18.5f)
        }
        path(fill = paint) {
            moveTo(14.4f, 12f)
            arcToRelative(2.4f, 2.4f, 0f, true, true, -4.8f, 0f)
            arcToRelative(2.4f, 2.4f, 0f, true, true, 4.8f, 0f)
            close()
        }
    }.build()
}
