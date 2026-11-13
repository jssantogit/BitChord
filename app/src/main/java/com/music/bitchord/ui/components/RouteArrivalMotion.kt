package com.music.bitchord.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Route-only arrival motion, not a second navigation host.
 *
 * Apple Music's exact tab and Settings timing is not yet traced by am-descomp.
 * This deliberately small movement is a BitChord response to abrupt page
 * switches: no outgoing copy, no two-page translucent crossfade, no new
 * destination and no animation on ordinary data/scroll recomposition.
 */
/** The secondary Library filter/sort popup has a single stable size.
 * Only its drawn content enters; dismissal and outside-tap remain immediate.
 * No Apple timing is inferred from the generic popup resource inventory. */
internal data class FrostedMenuEntranceFrame(val alpha: Float, val scale: Float)

internal fun frostedMenuEntranceFrame(progress: Float, reducedMotion: Boolean): FrostedMenuEntranceFrame {
    val p = if (reducedMotion || !progress.isFinite()) 1f else progress.coerceIn(0f, 1f)
    return FrostedMenuEntranceFrame(alpha = 0.3f + 0.7f * p, scale = 0.94f + 0.06f * p)
}

@Composable
internal fun rememberFrostedMenuEntranceModifier(reduceMotion: Boolean): Modifier {
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
    }
    return Modifier.graphicsLayer {
        val frame = frostedMenuEntranceFrame(progress.value, reduceMotion)
        alpha = frame.alpha
        scaleX = frame.scale
        scaleY = frame.scale
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
    }
}

internal data class RouteArrivalFrame(val alpha: Float, val translationDp: Float)

internal fun routeArrivalFrame(progress: Float, forward: Boolean): RouteArrivalFrame {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    // +0 and -0 compare differently in a Float-backed data class. At rest
    // there must be one canonical frame regardless of entry direction.
    if (p == 1f) return RouteArrivalFrame(alpha = 1f, translationDp = 0f)
    return RouteArrivalFrame(
        alpha = 0.96f + 0.04f * p,
        translationDp = (if (forward) 1f else -1f) * 12f * (1f - p),
    )
}

/**
 * Root tabs are peers, not a forward/back navigation stack. Never slide a
 * full-screen Home/Search/Library feed from either side; the apparent rocking
 * came from direction-dependent 12dp translation of AnimatedContent itself.
 *
 * A near-opaque, short arrival keeps the switch soft without exposing the
 * transparent window below an overlapping crossfade. This is BitChord motion,
 * not a measured Apple Music timing.
 */
internal fun rootTabArrivalFrame(progress: Float): RouteArrivalFrame {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    return RouteArrivalFrame(alpha = 0.975f + 0.025f * p, translationDp = 0f)
}

/** Disable AnimatedContent's implicit SizeTransform spring for root peers. */
internal fun rootTabContentTransform(): ContentTransform = ContentTransform(
    targetContentEnter = EnterTransition.None,
    initialContentExit = ExitTransition.None,
    sizeTransform = null,
)

internal fun shouldAnimateRouteArrival(
    previous: String,
    next: String,
    reduced: Boolean,
    rootTabPrefix: String? = null,
): Boolean =
    !reduced && previous != next &&
        (rootTabPrefix == null ||
            (previous.startsWith(rootTabPrefix) && next.startsWith(rootTabPrefix)))

/**
 * The current destination enters over the already-stable page background.
 * The outgoing route is not kept alive for a second crossfade. `rootTabPrefix`
 * limits the effect to root-tab-to-root-tab switches when used on MainActivity.
 */
@Composable
internal fun rememberRouteArrivalModifier(
    routeKey: String,
    reduceMotion: Boolean,
    forward: Boolean = true,
    rootTabPrefix: String? = null,
): Modifier {
    val progress = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(routeKey) }
    var arrivingForward by remember { mutableStateOf(forward) }

    LaunchedEffect(routeKey, reduceMotion) {
        val animate = shouldAnimateRouteArrival(
            previous = previous,
            next = routeKey,
            reduced = reduceMotion,
            rootTabPrefix = rootTabPrefix,
        )
        previous = routeKey
        if (animate) {
            arrivingForward = forward
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = if (rootTabPrefix != null) 160 else 185,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            progress.snapTo(1f)
        }
    }

    return Modifier.graphicsLayer {
        val frame = if (rootTabPrefix == null) {
            routeArrivalFrame(progress.value, arrivingForward)
        } else {
            rootTabArrivalFrame(progress.value)
        }
        alpha = frame.alpha
        translationX = frame.translationDp.dp.toPx()
    }
}
