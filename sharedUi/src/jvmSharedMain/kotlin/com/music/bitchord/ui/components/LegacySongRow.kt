package com.music.bitchord.ui.components

import com.music.bitchord.ui.LocalPullToRefreshEnabled
import com.music.bitchord.ui.AppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.ui.player.MESH_CYCLE_MILLIS
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.WelcomeMeshPalettes
import com.music.bitchord.ui.player.cyclingMeshPalette
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isMatchPending
import com.music.bitchord.data.model.isMatchMissing
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.CircularProgressIndicator
import com.music.bitchord.data.model.artworkAt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border

/** Compatibility boundary for old provider-native Song lists.
 * No new canonical screen should depend on this adapter.
 * Retained temporarily to preserve existing offline, downloads and menu flows.
 */

/**
 * One track row, used by search, library and detail pages.
 *
 * Swiping it either way queues the track or plays it next, per
 * [AppSettings.swipeToPlayNext] — the row springs back rather than
 * dismissing, since nothing is being removed. Long-press opens the actions
 * menu.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    /**
     * What the trailing ⋮ does, when it should differ from [onLongPress].
     *
     * Defaults to the long-press, since on most pages the two are the same
     * menu. Downloads is the exception: holding a row there starts a
     * multi-selection, and the ⋮ has to stay the actions sheet rather than
     * silently tick a checkbox.
     */
    onMore: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
    /**
     * What the row paints over the swipe reveal as it slides back.
     *
     * It has to be the colour of the page the row is *on*, not the theme's
     * background — an album page tinted from its sleeve would otherwise drag a
     * black band across itself on every swipe.
     */
    rowBackground: Color = MaterialTheme.colorScheme.background,
    /**
     * Drawn in place of the artwork, for lists where every row would otherwise
     * repeat the same cover — an album's own track listing.
     */
    trackNumber: Int? = null,
    /**
     * The artist line, and the track number when there is one.
     *
     * A page tinted from its artwork wants this brighter than the flat feeds
     * do: the usual dim grey is pitched against black, and against a mid-toned
     * wash it stops being legible as a second line and starts disappearing.
     */
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /**
     * What the badge on an already-downloaded row is tinted, or null to leave
     * those rows unmarked.
     *
     * Null is for the Downloads page itself, where every row qualifies and the
     * badge would say nothing. The colour is a parameter for the same reason
     * [subtitleColor] is: a page tinted from its artwork draws its accent from
     * the sleeve, and the theme's primary against that wash is exactly the
     * kind of thing that reads as pasted on.
     */
    downloadedTint: Color? = MaterialTheme.colorScheme.primary,
    /** Whether this row is the current item in the player's queue. */
    isCurrent: Boolean = false,
    /** Distinguishes active playback from the same current item while paused. */
    isPlaying: Boolean = false,
    /** Accent supplied by artwork-tinted pages. */
    activeTint: Color = MaterialTheme.colorScheme.primary,
    /** Search results use artwork bars instead of the usual current-song icon. */
    searchPlayingStyle: Boolean = false,
    /** True while a Downloads row belongs to the current multi-selection. */
    selected: Boolean = false,
    /**
     * Leads the artist line with the tier the row's source offered — Lossless,
     * Hi-Res Lossless, Dolby Atmos, High quality — for lists where rows from
     * different catalogues sit side by side: the Addons search.
     */
    showSourceQuality: Boolean = false,
) {
    NativeDetailTrackSwipeContainer(
        onSwipeToQueue = onSwipeToQueue,
        rowBackground = rowBackground,
        modifier = modifier,
    ) { rowModifier ->
        SongRowContent(
            song = song,
            onClick = onClick,
            onLongPress = onLongPress,
            onMore = onMore ?: onLongPress,
            modifier = rowModifier,
            trackNumber = trackNumber,
            subtitleColor = subtitleColor,
            downloadedTint = downloadedTint,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            activeTint = activeTint,
            searchPlayingStyle = searchPlayingStyle,
            selected = selected,
            showSourceQuality = showSourceQuality,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    downloadedTint: Color? = MaterialTheme.colorScheme.primary,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    searchPlayingStyle: Boolean = false,
    selected: Boolean = false,
    showSourceQuality: Boolean = false,
) {
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) activeTint else MaterialTheme.colorScheme.onBackground,
        label = "song row title",
    )
    val activeBackground by animateColorAsState(
        targetValue = if (isCurrent || selected) activeTint.copy(alpha = 0.14f) else Color.Transparent,
        label = "song row background",
    )
    // A Spotify track still being matched to a YouTube Music song, or one that
    // has no match: shown, but not something a tap or a menu can act on.
    val matching = song.isMatchPending
    val unavailable = song.isMatchMissing
    NativeDetailTrackRowFrame(
        onClick = onClick,
        onLongPress = onLongPress,
        onMore = onMore,
        modifier = modifier,
        opacity = if (unavailable) 0.45f else 1f,
        background = activeBackground,
    ) {
        if (trackNumber != null) {
            NativeDetailTrackNumber(
                number = trackNumber,
                color = subtitleColor,
                showPlayingBars = searchPlayingStyle && isCurrent && isPlaying,
            )
        } else {
            NativeDetailTrackArtwork(
                showPlayingBars = searchPlayingStyle && isCurrent && isPlaying,
            ) {
                AsyncImage(
                    model = com.music.bitchord.ui.player.rememberPlaybackArtworkImageModel(song, ROW_ART_PX),
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .thumbnailBorder(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        // A number is followed by the page gutter again, so the gap from the
        // screen edge to the number and from the number to the title match,
        // whatever its digit count.
        Spacer(Modifier.width(if (trackNumber == null) 14.dp else PAGE_GUTTER))
        NativeTrackRowText(
            title = {
                ExplicitSongTitle(
                    song = song,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                )
            },
            subtitle = {
                val subtitle = listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.downloadFormat,
                ).joinToString(" · ")
                val quality = if (showSourceQuality) song.sourceQualityBadge() else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (quality != null) {
                        SourceQualityBadge(
                            quality = quality, color = subtitleColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = if (quality != null && subtitle.isNotEmpty()) " · $subtitle" else subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
        if (downloadedTint != null) {
            DownloadedBadge(song.videoId, downloadedTint)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(Res.string.selected),
                tint = activeTint,
                modifier = Modifier.size(20.dp),
            )
        }
        // Rating occupies the trailing strip; title/artist ellipsize to leave
        // space for it. Song lists no longer show per-track duration.
        NativeDetailTrackRatingSlot {
            CanonicalSongRatingStars(song, color = MaterialTheme.colorScheme.onSurface)
            if (matching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = subtitleColor,
                )
            }
        }
        // Same sheet the long-press opens, for anyone who doesn't think to hold.
        if (onMore != null && !matching && !unavailable) {
            RowMoreButton(onClick = onMore, tint = subtitleColor)
        }
    }
}

/**
 * The mark on a row whose track is already on disk.
 *
 * Sized under the row's "more" glyph on purpose: this is a statement about the
 * track, not something to press, and a status mark that matches an affordance
 * in weight invites a tap that does nothing.
 *
 * Reads the downloads the app keeps in memory ([AppUiHost.downloadedIds]) rather than touching the filesystem — a list cannot
 * afford a file check per row, and the map is kept honest by the disk check
 * every real read of a download goes through. The cost of that trade is a row
 * that can claim a file a file manager has since deleted, until something asks
 * for it and the record is pruned.
 */
@Composable
fun DownloadedBadge(videoId: String, tint: Color, modifier: Modifier = Modifier) {
    if (videoId !in AppUi.host.downloadedIds()) return
    Spacer(Modifier.width(8.dp))
    Icon(
        Icons.Rounded.DownloadDone,
        contentDescription = stringResource(Res.string.downloaded),
        tint = tint,
        modifier = modifier.size(16.dp),
    )
}

