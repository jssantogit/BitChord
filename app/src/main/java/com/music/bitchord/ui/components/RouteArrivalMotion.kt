package com.music.bitchord.ui.components

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
                animationSpec = tween(durationMillis = 185, easing = FastOutSlowInEasing),
            )
        } else {
            progress.snapTo(1f)
        }
    }

    return Modifier.graphicsLayer {
        val frame = routeArrivalFrame(progress.value, arrivingForward)
        alpha = frame.alpha
        translationX = frame.translationDp.dp.toPx()
    }
}
