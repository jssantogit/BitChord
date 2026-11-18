package com.music.bitchord.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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

/**
 * The AM-DECOMP #7 static tab transaction binds @animator/nav_default_*
 * alpha enter/exit to 150ms linear. It does not prove video timing or
 * tab-indicator motion. Keep both fades on the content only, never on chrome.
 * Do not layer the previous 160ms host arrival over these two fades.
 */
internal fun rootTabFadeMillis(reducedMotion: Boolean): Int = if (reducedMotion) 0 else 150

/** Root peers must not inherit AnimatedContent's SizeTransform spring. */
internal fun rootTabContentTransform(reducedMotion: Boolean = false): ContentTransform {
    val duration = rootTabFadeMillis(reducedMotion)
    return ContentTransform(
        targetContentEnter = if (duration == 0) EnterTransition.None else
            fadeIn(animationSpec = tween(duration, easing = LinearEasing)),
        initialContentExit = if (duration == 0) ExitTransition.None else
            fadeOut(animationSpec = tween(duration, easing = LinearEasing)),
        sizeTransform = null,
    )
}

/**
 * Search is a new active surface, and the playlist creator is a pushed
 * full-page wizard. Both must replace the retiring composition atomically:
 * even a brief 150ms root-tab crossfade showed old album covers/controls over
 * Search, and the isolated 180ms builder arrival could retain Library rows.
 *
 * This is deliberately limited to these routes. Other root-tab transitions
 * and pushed-page animations remain unchanged.
 */
internal fun mainPageMustReplaceImmediately(
    previous: String,
    next: String,
    rootTabPrefix: String,
    searchTabKey: String,
): Boolean {
    if (previous == next || !searchTabKey.startsWith(rootTabPrefix)) return false
    return previous == searchTabKey || next == searchTabKey ||
        previous == "smart_playlist_builder" || next == "smart_playlist_builder"
}

/** Both slots switch without a fade or size spring; no translucent overlap. */
internal fun immediatePageContentTransform(reducedMotion: Boolean = false): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        sizeTransform = null,
    )

/** Only two root tabs may crossfade each other's *current* content.
 * Pushed pages and nested destinations must not show the retiring route
 * during their own initial loading frame. */
internal fun mainPageRequiresImmediateExit(
    previous: String,
    next: String,
    rootTabPrefix: String,
): Boolean = !(previous.startsWith(rootTabPrefix) && next.startsWith(rootTabPrefix))

/** The outgoing page is gone on the first frame; the incoming page may fade in
 * over the stable host background, without resurrecting stale route content. */
internal fun isolatedPageContentTransform(reducedMotion: Boolean): ContentTransform =
    ContentTransform(
        targetContentEnter = if (reducedMotion) EnterTransition.None else
            fadeIn(animationSpec = tween(180)),
        // AnimatedContent retains the outgoing composition while enter runs.
        // None would leave those pixels visible; a zero-duration alpha exit
        // suppresses them immediately without altering the incoming motion.
        initialContentExit = if (reducedMotion) ExitTransition.None else
            fadeOut(animationSpec = tween(0)),
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
                    durationMillis = if (rootTabPrefix != null) rootTabFadeMillis(reduceMotion) else 185,
                    easing = if (rootTabPrefix != null) LinearEasing else FastOutSlowInEasing,
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
