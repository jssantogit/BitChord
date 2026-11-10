package com.music.bitchord.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.TextUnit
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    // Screenshot comparison: reference side gap is ~5.1% vs ~2.6% in our first smoke.
    // 20dp is a visual calibration, not an AM-DECOMP XML measurement.
    val pillHorizontalInset = 20.dp
    val navigationIconSize = 30.dp
    val navigationLabelSize = 13.sp

    val miniPlayerHeight = 48.dp
    val artworkSize = 34.dp
    val controlTouchSize = 40.dp
    val playGlyphSize = 32.dp
    val nextGlyphSize = 32.dp
    // XML base: mini_player_title = FootnoteEmphasized (13sp),
    // mini_player_artist = Caption1 (12sp). Wider variants use 15sp,
    // but their active resource qualifier was not observed in the reference.
    val titleSize = 13.sp
    val artistSize = 12.sp
}

/**
 * Layout qualifiers in am-descomp: base mini_player.xml uses
 * FootnoteEmphasized 13sp / Caption1 12sp; layout-w675dp, -w735dp and
 * -w830dp use SubheadSemiBold/Subhead 15sp. These are XML inputs rather
 * than measurements of the reference device or typeface resolution.
 *
 * Android Configuration.screenWidthDp follows the available-width family
 * used to select wNNNdp resources; no invented pixel-density conversion.
 */
internal data class AppleMiniPlayerTextSpec(
    val titleSize: TextUnit,
    val artistSize: TextUnit,
    val titleWeight: FontWeight,
)

internal fun appleMiniPlayerTextForWidthDp(availableWidthDp: Int): AppleMiniPlayerTextSpec =
    if (availableWidthDp >= 675) {
        AppleMiniPlayerTextSpec(15.sp, 15.sp, FontWeight.SemiBold)
    } else {
        // The bundled font's nearest supported weight to the Apple
        // variable font's declared 510 is Medium (500).
        AppleMiniPlayerTextSpec(
            AppleMusicChromeMetrics.titleSize,
            AppleMusicChromeMetrics.artistSize,
            FontWeight.Medium,
        )
    }

/**
 * Experimental motion values, not extracted Apple Music animation timings.
 * AM-DECOMP documents the tab tint state and player artwork geometry, but does
 * NOT establish tab-selection, transport or mini-player crossfade durations.
 */
internal object AppleMusicChromeMotion {
    const val tabTintMillis = GlobalMotionPolicy.selectionTintMillis
    const val trackCrossfadeMillis = 220
    const val transportCrossfadeMillis = 150
    const val miniAppearMillis = 220
    const val miniDisappearMillis = 180

    fun duration(baseMillis: Int, reduced: Boolean): Int =
        GlobalMotionPolicy.duration(baseMillis, reduced)
}

/**
 * A real queue-entry change animates artwork and byline together.
 * Changing artwork URL, quality, artist metadata, source or download flags
 * on the *same* queue entry must not replay two crossfades or decode an
 * outgoing duplicate image. Those fields still update inside that slot.
 *
 * The fallback handles historical queues without assigned queueEntryId.
 */
internal fun appleMiniPlayerContentKey(song: Song): String =
    song.queueEntryId?.takeIf { it.isNotBlank() }?.let { "queue:$it" }
        ?: song.videoId.takeIf { it.isNotBlank() }?.let { "media:$it" }
        ?: song.localUri?.takeIf { it.isNotBlank() }?.let { "local:$it" }
        ?: "unresolved:${song.title}:${song.artist}"

/** A disabled slot (Radio) never reacts to press or selection animations. */
internal fun appleMusicTabScaleTarget(enabled: Boolean, selected: Boolean, pressed: Boolean): Float =
    when {
        !enabled -> 1f
        pressed -> 0.91f
        selected -> 1.04f
        else -> 1f
}

/**
 * The pointer drives a bounded visual displacement, not a composition state
 * transition for every pixel. Drag position is only read in graphicsLayer.
 */
internal fun miniPlayerSwipeVisualOffset(distancePx: Float, thresholdPx: Float, maxPx: Float): Float =
    if (!distancePx.isFinite() || thresholdPx <= 0f || maxPx <= 0f) 0f
    else (distancePx / thresholdPx).coerceIn(-1f, 1f) * maxPx

@Composable
private fun appleMusicPressScale(pressed: Boolean, reduced: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = GlobalMotionPolicy.pressedScale(pressed, reduced, activeScale = 0.9f),
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.80f, stiffness = 510f),
        label = "appleMusicTransportPress",
    )
    return scale
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

/**
 * Exact vector drawables recovered from Apple Music Android 7.0.0 (build 1614).
 * The AM-DECOMP static menu binds these five resources to the five tab slots;
 * the experimental BitChord destinations remain unchanged. Source/provenance:
 * docs/apple-music-original-icon-experiment.md
 */
internal fun appleMusicTabIconForVisualIndex(visibleIndex: Int): Int = when (visibleIndex) {
    0 -> R.drawable.apple_music_tab_home
    1 -> R.drawable.apple_music_tab_explore
    2 -> R.drawable.apple_music_tab_radio
    3 -> R.drawable.apple_music_tab_library
    4 -> R.drawable.apple_music_tab_search
    else -> error("Unknown Apple Music visual tab index: $visibleIndex")
}

/** True when playing: the original pause icon is shown, otherwise original play. */
internal fun appleMusicMiniPlayerPlayPauseIcon(isPlaying: Boolean): Int =
    if (isPlaying) R.drawable.apple_music_mp_pause else R.drawable.apple_music_mp_play

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
    val visualLabels = listOf(tabs[0].label, tabs[1].label, radioLabel, tabs[2].label, tabs[3].label)
    val selectedVisibleIndex = appleMusicVisualIndexForDestination(selectedIndex)
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    Row(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = AppleMusicChromeMetrics.pillHorizontalInset)
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .height(AppleMusicChromeMetrics.navigationHeight)
            .appleMusicChrome(hazeState)
            .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visualLabels.forEachIndexed { visibleIndex, label ->
            val destination = appleMusicDestinationForVisualIndex(visibleIndex)
            val enabled = destination != null
            val selected = selectedVisibleIndex == visibleIndex
            val targetTint = when {
                !enabled -> AppleWhite.copy(alpha = 0.34f)
                selected -> AppleBlue
                else -> AppleWhite
            }
            val tint by animateColorAsState(
                targetValue = targetTint,
                animationSpec = tween(
                    durationMillis = AppleMusicChromeMotion.duration(
                        AppleMusicChromeMotion.tabTintMillis, reduceMotion,
                    ),
                ),
                label = "appleMusicTabTint",
            )
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = appleMusicTabScaleTarget(enabled, selected, pressed),
                animationSpec = if (reduceMotion) snap() else spring(
                    dampingRatio = 0.82f, stiffness = 500f,
                ),
                label = "appleMusicTabScale",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        interactionSource = interaction,
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
                    painter = painterResource(appleMusicTabIconForVisualIndex(visibleIndex)),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(AppleMusicChromeMetrics.navigationIconSize)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
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
@OptIn(ExperimentalAnimationApi::class)
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
    val miniText = appleMiniPlayerTextForWidthDp(
        LocalConfiguration.current.screenWidthDp,
    )
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val trackFade = AppleMusicChromeMotion.duration(
        AppleMusicChromeMotion.trackCrossfadeMillis, reduceMotion,
    )
    val controlFade = AppleMusicChromeMotion.duration(
        AppleMusicChromeMotion.transportCrossfadeMillis, reduceMotion,
    )
    // One transition coordinates the outgoing artwork and metadata, with a
    // content key bound to the queue item, not every changing Song field.
    val trackIdentity = appleMiniPlayerContentKey(song)
    val trackTransition = updateTransition(targetState = song, label = "appleMusicMiniTrack")
    val playInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val playPressed by playInteraction.collectIsPressedAsState()
    val nextPressed by nextInteraction.collectIsPressedAsState()
    val playScale = appleMusicPressScale(playPressed, reduceMotion)
    val nextScale = appleMusicPressScale(nextPressed, reduceMotion)

    // The shared recognizer already owns the skip/expand decision.
    // Pointer updates should invalidate ONLY this row's graphics layer,
    // not recompose artwork/text or restart a crossfade for every drag pixel.
    val swipeDistance = remember { mutableFloatStateOf(0f) }
    val swipeActive = remember { mutableStateOf(false) }
    val swipeSettle = remember { Animatable(0f) }
    val settleJob = remember { arrayOfNulls<Job>(1) }
    val settleScope = rememberCoroutineScope()
    val swipeLimitPx = with(LocalDensity.current) { 72.dp.toPx() }
    val swipeMaxPx = with(LocalDensity.current) { 12.dp.toPx() }
    // A queue replacement also stops the OLD gesture and removes any old
    // settle offset before the NEW artwork appears. Artwork/quality hydration
    // retains the same identity and therefore does not interrupt the drag.
    LaunchedEffect(trackIdentity) {
        settleJob[0]?.cancel()
        swipeActive.value = false
        swipeDistance.floatValue = 0f
        swipeSettle.snapTo(0f)
    }
    // A preference toggle during settling cancels the running animation,
    // including any swipe whose pointer has not yet lifted.
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            settleJob[0]?.cancel()
            swipeActive.value = false
            swipeDistance.floatValue = 0f
            swipeSettle.snapTo(0f)
        }
    }
    Box(
        modifier = modifier
            .padding(horizontal = AppleMusicChromeMetrics.pillHorizontalInset)
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
                gestureIdentity = trackIdentity,
                onHorizontalSwipe = { distancePx, active ->
                    if (active) {
                        if (!swipeActive.value) {
                            settleJob[0]?.cancel()
                            swipeActive.value = true
                        }
                        swipeDistance.floatValue = distancePx
                    } else if (swipeActive.value) {
                        val releaseOffset = miniPlayerSwipeVisualOffset(
                            swipeDistance.floatValue, swipeLimitPx, swipeMaxPx,
                        )
                        settleJob[0]?.cancel()
                        if (reduceMotion) {
                            swipeActive.value = false
                            swipeDistance.floatValue = 0f
                        } else {
                            settleJob[0] = settleScope.launch {
                                // Snap to the last finger position *before*
                                // switching the draw layer to its settling
                                // state, avoiding a one-frame jump to zero.
                                swipeSettle.snapTo(releaseOffset)
                                swipeActive.value = false
                                swipeDistance.floatValue = 0f
                                swipeSettle.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.78f, stiffness = 400f),
                                )
                            }
                        }
                    }
                },
            )
            .fillMaxWidth()
            .height(AppleMusicChromeMetrics.miniPlayerHeight)
            .appleMusicChrome(hazeState)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppleMusicChromeMetrics.miniPlayerHeight)
                .padding(horizontal = 12.dp)
                .graphicsLayer {
                    translationX = when {
                        reduceMotion -> 0f
                        swipeActive.value -> miniPlayerSwipeVisualOffset(
                            swipeDistance.floatValue, swipeLimitPx, swipeMaxPx,
                        )
                        else -> swipeSettle.value
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artCorner = RoundedCornerShape(6.dp)
            // Keep exactly one dock coordinate reporter during the artwork
            // crossfade. Two dock reporters would compete during a track skip.
            Box(
                modifier = Modifier
                    .size(AppleMusicChromeMetrics.artworkSize)
                    .playerDockArt(dock, 6.dp)
                    .clip(artCorner)
                    .thumbnailBorder(artCorner)
                    .background(AppleNightSurface),
            ) {
                trackTransition.Crossfade(
                    animationSpec = tween(trackFade),
                    contentKey = ::appleMiniPlayerContentKey,
                ) { displayedSong ->
                    AsyncImage(
                        model = rememberPlaybackArtworkImageModel(displayedSong, ROW_ART_PX),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            trackTransition.Crossfade(
                animationSpec = tween(trackFade),
                modifier = Modifier.weight(1f),
                contentKey = ::appleMiniPlayerContentKey,
            ) { displayedSong ->
                Column(verticalArrangement = Arrangement.Center) {
                    ExplicitSongTitle(
                        song = displayedSong,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = miniText.titleSize,
                            lineHeight = 18.sp,
                            fontWeight = miniText.titleWeight,
                        ),
                        color = AppleWhite,
                    )
                    Text(
                        text = displayedSong.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = miniText.artistSize,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = AppleSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = {
                    haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                    onPlayPause()
                },
                enabled = !isLoading,
                interactionSource = playInteraction,
                modifier = Modifier
                    .size(AppleMusicChromeMetrics.controlTouchSize)
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                    },
            ) {
                Crossfade(
                    targetState = isLoading to isPlaying,
                    animationSpec = tween(controlFade),
                    label = "appleMusicMiniPlaybackState",
                ) { (loading, playing) ->
                    if (loading) {
                        MiniPlayerLoadingIndicator(
                            color = AppleWhite,
                            size = 20.dp,
                            reduceMotion = reduceMotion,
                        )
                    } else {
                        Icon(
                            painter = painterResource(appleMusicMiniPlayerPlayPauseIcon(playing)),
                            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                            tint = AppleWhite,
                            modifier = Modifier.size(AppleMusicChromeMetrics.playGlyphSize),
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                enabled = !controlsLocked,
                interactionSource = nextInteraction,
                modifier = Modifier
                    .size(AppleMusicChromeMetrics.controlTouchSize)
                    .graphicsLayer {
                        scaleX = nextScale
                        scaleY = nextScale
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.apple_music_mp_next),
                    contentDescription = stringResource(R.string.widget_next),
                    tint = AppleWhite.copy(alpha = if (controlsLocked) 0.34f else 1f),
                    modifier = Modifier.size(AppleMusicChromeMetrics.nextGlyphSize),
                )
            }
        }
    }
}
