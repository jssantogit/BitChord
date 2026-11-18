package com.music.bitchord.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlin.math.roundToInt

/**
 * Everything on an artist page that is colour rather than words: the photo,
 * with its foot dissolved into the solid [ArtworkPalette.wash] the page is
 * painted in. A release page has no such layer — its sleeve is framed.
 *
 * A layer of its own so the list can be scrolled over it rather than carry it.
 * It follows the scroll instead of being scrolled: the list owns the gesture
 * and reserves the room, and the picture is offset to follow whatever the list
 * did with item zero. Read in a placement block, so a scroll moves it without
 * recomposing anything.
 *
 * The join used to be hidden by a live blur laid across it, re-run on every
 * frame of every scroll. A blurred copy of the sleeve does the same job here
 * drawn once: nothing about it changes as the page moves, so it is rasterised
 * on the first frame and only composited after that.
 */
/** A missing artist photo is a color-only hero, not a Coil request for null. */
internal fun hasNativeArtistBackdropStill(url: String?): Boolean = !url.isNullOrBlank()

@Composable
internal fun PageBackground(
    palette: ArtworkPalette,
    canvas: CanvasArtwork?,
    artHeight: Dp,
    listState: LazyListState,
    heroUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val art = heroUrl?.takeIf(::hasNativeArtistBackdropStill)
    // Do not allocate two empty painters for an artist with no portrait.
    // A still over a moving canvas would also freeze the animation's foot.
    val softenFoot = art != null && canvas == null && !reduceDynamicBlur &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier.clipToBounds()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(artHeight)
                .offset { IntOffset(0, listState.headerTop(artHeight.toPx()).roundToInt()) },
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .background(palette.elevated),
                )
            } else {
                // The header may still be compact and show the artist's name.
                // No spurious gray rectangle should sit above the page wash.
                Box(Modifier.matchParentSize().background(palette.wash))
            }

            // Above the still art but below the scrim, so the scrim that
            // settles the header into the page still sits over it. Always
            // running: unlike the player's sleeve there is no transport here
            // to follow, and the page is only up while it's being read.
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = true,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // The same sleeve, blurred and faded in over the lower half, so the
            // picture loses its detail before it loses its colour — a merge
            // rather than a fade to a flat tint. The same request as the sharp
            // copy, so it is a memory-cache hit and lands on the same frame.
            if (softenFoot) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        // Offscreen so the mask cuts the blurred result rather
                        // than each draw beneath it.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(SOFT_FOOT_MASK, blendMode = BlendMode.DstIn)
                        }
                        // Rectangle keeps the edges clamped to the picture's own
                        // colour rather than fading to transparent at the foot.
                        .blur(SOFT_FOOT_BLUR, BlurredEdgeTreatment.Rectangle),
                )
            }

            // Ends on the page colour at full strength, so there is no edge
            // left where the artwork stops. Eased rather than run straight: a
            // gradient that changes slope at a stop shows a line at that stop,
            // however close the colours either side of it are.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            0.65f to palette.wash.copy(alpha = 0.30f),
                            0.82f to palette.wash.copy(alpha = 0.72f),
                            0.94f to palette.wash.copy(alpha = 0.95f),
                            1.00f to palette.wash,
                        ),
                    ),
            )
        }
    }
}

/**
 * Where the top of the artwork currently is.
 *
 * The list is the one being scrolled; the background only has to agree with it.
 * While the header is item zero and on screen, how far it has been scrolled off
 * the top is exactly the offset the picture behind it needs. Once it isn't,
 * there is nothing to agree with, and the picture parks two artwork-heights up
 * — far enough that no part of it comes back down.
 */
private fun LazyListState.headerTop(artHeightPx: Float): Float =
    if (firstVisibleItemIndex == 0) -firstVisibleItemScrollOffset.toFloat() else -artHeightPx * 2f

/** Where the blurred copy starts to show and where it has fully taken over. */
private val SOFT_FOOT_MASK = Brush.verticalGradient(
    0.35f to Color.Transparent,
    0.75f to Color.Black,
)

/**
 * Wide enough that no shapes survive where the blurred copy is at full
 * strength — a blur that leaves them reads as a blurred photograph, and a
 * blurred photograph next to a flat colour is still two surfaces.
 */
private val SOFT_FOOT_BLUR = 48.dp

