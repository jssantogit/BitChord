package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/**
 * The bar's own height, above whatever inset it is sitting under.
 *
 * The single source of truth for it: the bar lays itself out to this, and
 * everything that has to clear the bar — page content padding, either top
 * backdrop, fixed headers that sit directly beneath it — measures from here
 * rather than from a copy of the number.
 */
val TopBarContentHeight = 52.dp

/**
 * The breathing room between the bar's bottom edge and the first thing under
 * it, so content rests below the glass instead of against it.
 */
val TopBarContentGap = 12.dp

/**
 * How much of each end of the bar is spoken for, so a long title truncates
 * instead of running under what sits there.
 *
 * Only ever consumed through the larger of the two — see the title's padding.
 * They are kept apart rather than collapsed into one number because they
 * describe two different things, and the wordmark is the one that changes when
 * the logo or the Dev badge does.
 */
private val BackInset = 54.dp
private val WordmarkInset = 96.dp
private val ActionsInset = 56.dp

/** Between a filling accessory and the actions pill. */
private val ACCESSORY_GAP = 8.dp

/** What the leading end of the bar needs: a back button, or the wordmark. */
private fun leadingInset(hasBack: Boolean): Dp = if (hasBack) BackInset else WordmarkInset

/**
 * How far down the window the bar actually ends: the status bar inset it is
 * pinned under, plus its own height.
 *
 * This has to be read at composition rather than baked in as a constant — the
 * inset is a property of the device and of the window, not of the app. A phone
 * with a cutout, one without, and a freeform window with no status bar at all
 * are all different numbers, and a fixed guess is wrong on all but one of them:
 * too tight and content is clipped under the bar, too loose and every page
 * opens on a band of empty space.
 */
@Composable
fun topBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarContentHeight

/**
 * Where page content should start: clear of the bar, plus [TopBarContentGap].
 */
@Composable
fun topBarContentPadding(): Dp = topBarHeight() + TopBarContentGap

/**
 * The top bar's content — title, back affordance, actions — over no blur of its
 * own.
 *
 * The app mounts it with [transparentBackdrop] and [artworkPageChrome] on every
 * page, in either material: no full-width pane, just floating circles over the
 * shared top gradient — liquid glass when that is on, the navbar's blur when it
 * is not. The bounded-pane path ([TopBarBlur] underneath, lower hairline, solid
 * fill under Reduce dynamic blur) is kept for previews only.
 *
 * Apple Music behaviour: the big in-list header owns the title at rest;
 * once the list scrolls, the small centered title fades in.
 */
@Composable
fun FrostedTopBar(
    title: String,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    /** Leaves the whole bar transparent so the page's own top gradient is the backdrop. */
    transparentBackdrop: Boolean = false,
    /** Circular back surface and no collapsing title, for artwork-led pages. */
    artworkPageChrome: Boolean = false,
    /** Source sampled by floating top-bar surfaces when liquid glass is off. */
    backButtonHazeState: HazeState? = null,
    trailingTitle: String? = null,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    // A lambda, not a value: the drag changes every frame, and reading it in
    // the caller would recompose the whole app on each one.
    pullFraction: () -> Float = { 0f },
    /**
     * A page control filling the bar from the leading gutter to [actions], on
     * no surface of its own — the Search tab's field, which brings its own.
     * Root tabs have no back button there for it to collide with.
     */
    accessory: (@Composable () -> Unit)? = null,
    /** No empty action capsule on the Home root when its menu lives in the header. */
    showActions: Boolean = true,
    separateActions: Boolean = false,
    /** Only root tabs with large in-list titles need the actions lowered to match. */
    rootActionsOffset: Dp = 0.dp,
    categoryBackButton: Boolean = false,
    backButtonOffset: Dp = 0.dp,
    /** Only Home opts in: other pages retain their own large page headings. */
    showScrolledTitle: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useFloatingChrome = artworkPageChrome ||
        (LocalLiquidGlassEnabled.current && isGlassSupported())
    val titleAlpha by animateFloatAsState(
        targetValue = if (scrolled || trailingTitle != null) 1f else 0f,
        animationSpec = tween(220),
        label = "topBarTitleAlpha",
    )
    // Every bounded bar uses the same hairline, whether its pane is blurred or
    // solid. A transparent artwork page has no pane edge for a line to mark.
    val dividerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline.copy(
            alpha = when {
                transparentBackdrop -> 0f
                else -> 0.6f
            },
        ),
        animationSpec = tween(220),
        label = "topBarDivider",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (reduceDynamicBlur && !transparentBackdrop) {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                } else Modifier,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TopBarContentHeight),
        ) {
            /* Temporarily hidden: keep the scrolling center-title implementation
             * intact so it can be restored without rebuilding its transitions.
             *
            // Artwork pages keep their large in-page heading and never create a
            // duplicate title in the status bar, including in accessibility.
            if (!artworkPageChrome) {
                AnimatedContent(
                    targetState = trailingTitle,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(260)) + slideInHorizontally(animationSpec = tween(260)) { it / 3 }) togetherWith
                            (fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200)) { -it / 3 })
                    },
                    label = "topBarTitleAnimation",
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Reserve room for whatever flanks the title — and reserve
                        // the *same* room on both sides. Equal is the whole point:
                        // the title is centered within this padded box, so an inset
                        // that differs end to end moves it off the bar's centre by
                        // half that difference. Reserving what each side actually
                        // needs (96dp for the wordmark, 56dp for the actions) put
                        // every root tab's title 20dp right of centre, which is
                        // visible against a status bar clock that is not.
                        .padding(horizontal = max(leadingInset(onBack != null), ActionsInset))
                        .fillMaxWidth()
                        .graphicsLayer { alpha = titleAlpha },
                ) { trailing ->
                    if (trailing != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = trailing,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
             */
            // The original all-page compact title was deliberately disabled.
            // Home alone opts into it as its large heading leaves the viewport.
            // This keeps the Apple Music-style hierarchy without duplicating
            // titles at the top of the unscrolled screen.
            if (showScrolledTitle && scrolled) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = max(leadingInset(false), ActionsInset))
                        .graphicsLayer { alpha = titleAlpha }
                        .testTag("home-compact-title"),
                )
            }
            // On a pushed page the back affordance is always visible, since
            // there is no large in-list header to fall back on.
            if (onBack != null) {
                if (useFloatingChrome) {
                    ArtworkPageBackButton(
                        onClick = onBack,
                        hazeState = backButtonHazeState,
                        size = if (categoryBackButton) AppleFloatingControlMetrics.libraryDiameter else 44.dp,
                        // The surface edge aligns with the floating navbar. The
                        // padding belongs outside the circle; its icon remains
                        // centred in the same 44dp control in every material.
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = BAR_GUTTER)
                            .offset(y = backButtonOffset),
                    )
                } else {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // No wordmark at the leading end: the root tabs carry it above
            // their large heading instead (LargePageTitle in :sharedUi).
            // The accessory takes everything from the leading gutter up to the
            // actions, which keep their own width at the trailing end.
            val edge = if (useFloatingChrome) BAR_GUTTER else 4.dp
            if (showActions || accessory != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(y = rootActionsOffset)
                        .then(if (accessory != null) Modifier.fillMaxWidth().padding(start = edge) else Modifier)
                        .padding(end = edge),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (accessory != null) {
                        Box(Modifier.weight(1f)) { accessory() }
                        if (showActions) Spacer(Modifier.width(ACCESSORY_GAP))
                    }
                    if (showActions) {
                        if (useFloatingChrome) {
                            if (separateActions) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
                            } else {
                                ArtworkPageActions(hazeState = backButtonHazeState, content = actions)
                            }
                        } else {
                            actions()
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
        RefreshPuck(refreshing = refreshing, pullFraction = pullFraction)
    }
}

/** Circular floating back affordance, material-matched to the navbar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun ArtworkPageBackButton(
    onClick: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val shape = CircleShape
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val contentColor = if (useLiquidGlass && !reduceDynamicBlur) {
        glassContentColor()
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .then(if (size == AppleFloatingControlMetrics.libraryDiameter) Modifier.testTag("apple-category-back-button") else Modifier)
            .then(artworkPageSurface(shape = shape, hazeState = hazeState)),
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = contentColor,
        )
    }
}

/** One independently surfaced circular action on the Apple Library root/category chrome.
 * The normal grouped pill remains unchanged on every other route.
 */
@Composable
internal fun FloatingLibraryTopBarCircle(
    hazeState: HazeState?,
    content: @Composable () -> Unit,
) {
    // The original APK's Library More Options path passes 40dp into its
    // rounded modifier (am-descomp: library-toolbar.md). This is the surface
    // dimension, not proof of the original final tap target or inset.
    Box(
        modifier = Modifier.size(AppleFloatingControlMetrics.libraryDiameter)
            .then(artworkPageSurface(shape = CircleShape, hazeState = hazeState)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Root Explore and Library share this compact Apple-style menu.
 *
 * am-descomp/docs/components/overflow-menu.md confirms a dark rounded popup
 * anchored near the top-right, but leaves exact dimensions and placement
 * unknown. The compact 180dp width, 17dp corner radius and 38dp offset are
 * screenshot-guided Bitchord values, not recovered Apple constants.
 *
 * Only Settings is exposed: Edit Pins remains blocked by am-descomp #1 and
 * account actions are intentionally not copied.
 */
@Composable
internal fun AppleRootSettingsOverflow(
    hazeState: HazeState?,
    description: String,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FloatingLibraryTopBarCircle(hazeState) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        AppleSettingsPopup(
            expanded = expanded,
            hazeState = hazeState,
            onDismiss = { expanded = false },
            onSettings = {
                expanded = false
                onSettings()
            },
        )
    }
}

/**
 * One shared menu across root tabs and pushed screens: the same compact
 * Settings action, not a per-screen fake account shortcut.
 * The 180dp/17dp/38dp measurements are screenshot-calibrated Bitchord
 * approximations; REA confirms popup content/placement class, not exact size.
 */
@Composable
private fun AppleSettingsPopup(
    expanded: Boolean,
    hazeState: HazeState?,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    if (!expanded) return
    val offsetPx = with(LocalDensity.current) { 38.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier.width(180.dp)
                .then(artworkPageSurface(shape = RoundedCornerShape(17.dp), hazeState = hazeState))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .18f))
                .testTag("apple-root-settings-popup"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .clickable(onClick = onSettings)
                    .testTag("apple-root-settings-item")
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Independent circular surfaces for top-bar icon actions across all routes.
 *
 * This uses the same floating material as Library's reference-backed 40dp
 * "More Options" control (am-descomp/library-toolbar.md). The generic 48dp
 * diameter is based on the documented alternate toolbar token, not an exact
 * measured bound for every Apple page.
 *
 * Each direct action is measured on its own before a separate surface is
 * subcomposed behind it. Text badges keep their existing width and do not
 * become fake circular buttons. Menus, callbacks and content remain untouched.
 */
@Composable
internal fun ArtworkPageActions(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val circleSize = AppleFloatingControlMetrics.toolbarDiameter
    val gap = AppleFloatingControlMetrics.preferredGap
    SubcomposeLayout(
        modifier = modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
    ) { constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val children = subcompose("actions", content).map { it.measure(childConstraints) }
        val diameter = circleSize.roundToPx()
        val gapPx = gap.roundToPx()
        val minIconBounds = 40.dp.roundToPx()
        val maxIconBounds = diameter + 4.dp.roundToPx()
        val surfaces = children.mapIndexed { index, child ->
            // Only genuine icon-sized slots receive a surface. Compact
            // informational badges and collapsed animations are not buttons.
            if (child.width < minIconBounds || child.height < minIconBounds ||
                child.width > maxIconBounds || child.height > maxIconBounds) null
            else subcompose("circle:$index") {
                Box(
                    Modifier.size(circleSize)
                        .then(artworkPageSurface(shape = CircleShape, hazeState = hazeState))
                        .testTag("apple-toolbar-action-circle"),
                )
            }.first().measure(Constraints.fixed(diameter, diameter))
        }
        val widths = children.indices.map { index ->
            maxOf(children[index].width, surfaces[index]?.width ?: 0)
        }
        val visibleIndices = children.indices.filter { index ->
            children[index].width > 0 && children[index].height > 0
        }
        val fittedGapPx = fittingToolbarActionGapPx(
            itemWidths = visibleIndices.map { widths[it] },
            availableWidthPx = constraints.maxWidth,
            preferredGapPx = gapPx,
        )
        val naturalWidth = visibleIndices.sumOf { widths[it] } +
            fittedGapPx * (visibleIndices.size - 1).coerceAtLeast(0)
        val naturalHeight = (visibleIndices.maxOfOrNull { index ->
            maxOf(children[index].height, surfaces[index]?.height ?: 0)
        } ?: 0)
        val width = naturalWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = naturalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            visibleIndices.forEachIndexed { order, index ->
                if (order > 0) x += fittedGapPx
                val child = children[index]
                val slotWidth = widths[index]
                surfaces[index]?.let { surface ->
                    surface.placeRelative(x + (slotWidth - surface.width) / 2, (height - surface.height) / 2)
                }
                child.placeRelative(x + (slotWidth - child.width) / 2, (height - child.height) / 2)
                x += slotWidth
            }
        }
    }
}

/** The navbar's exact material choice, reusable by every floating top control. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun artworkPageSurface(
    shape: CornerBasedShape,
    hazeState: HazeState?,
): Modifier {
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useLiquidGlass = LocalLiquidGlassEnabled.current && isGlassSupported()

    return Modifier
        .clip(shape)
        .then(
            when {
                useLiquidGlass -> Modifier.liquidGlass(shape)
                reduceDynamicBlur || hazeState == null -> Modifier.background(container)
                else -> Modifier.optimizedHazeEffect(
                    state = hazeState,
                    style = HazeMaterials.regular(container),
                )
            },
        )
        // Keep the same explicit hairline as FloatingBottomBar. Liquid glass
        // also has its refractive highlight, but the navbar retains this edge
        // so these surfaces do as well.
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * Shared Settings overflow on every pushed screen and root outside the special
 * Library chrome. Old account-photo entry is intentionally retired: ⋮ opens a
 * small popup and Settings remains the existing destination. Account switching
 * stays available from Settings, not by a hidden gesture on a different glyph.
 *
 * Keep the legacy parameters for callers while the toolbar routes are unified.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TopBarAccountButton(
    account: Account?,
    onClick: () -> Unit,
    onSwipeProfile: ((forward: Boolean) -> Boolean)? = null,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(AppleFloatingControlMetrics.toolbarDiameter)
                .testTag("apple-global-more-options"),
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.more),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(AppleFloatingControlMetrics.toolbarGlyphSize),
            )
        }
        AppleSettingsPopup(
            expanded = expanded,
            hazeState = hazeState,
            onDismiss = { expanded = false },
            onSettings = {
                expanded = false
                onClick()
            },
        )
    }
}

/**
 * The refresh indicator: a round puck that slides out from under the bar's
 * bottom edge as the list is pulled, Chrome-style.
 *
 * It is drawn by the bar rather than by the pull-to-refresh box, so it lands on
 * top of the glass instead of behind it, and it is clipped to the band just
 * below the bar so it emerges from the edge instead of appearing over the
 * status bar. Its position is the pull state's own distance fraction — which
 * the pull box holds at 1 while refreshing and animates back to 0 when done —
 * so the slide down, the rest while loading and the slide back up are all one
 * value. The arc fills with the drag, then spins once the refresh is away.
 *
 * Laid out at zero height, so the bar keeps its size and the puck overhangs
 * the content without taking any touches from it.
 */
@Composable
private fun RefreshPuck(refreshing: Boolean, pullFraction: () -> Float, modifier: Modifier = Modifier) {
    val currentFraction by rememberUpdatedState(pullFraction)
    val pulling by remember { derivedStateOf { currentFraction() > 0f } }
    if (!refreshing && !pulling) return

    val travel = with(LocalDensity.current) { (PUCK_REST + PUCK_SIZE).toPx() }
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                layout(placeable.width, 0) { placeable.place(0, 0) }
            }
            .fillMaxWidth()
            .height(PUCK_REST + PUCK_SIZE + PUCK_OVERSHOOT + 12.dp)
            .clipToBounds(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val f = currentFraction().coerceIn(0f, 2f)
                    // Past the threshold the puck keeps following, but
                    // reluctantly, like the list under it.
                    val eased = if (f <= 1f) f else 1f + (f - 1f) * 0.35f
                    translationY = travel * eased - PUCK_SIZE.toPx()
                    alpha = (f * 3f).coerceAtMost(1f)
                }
                .shadow(6.dp, CircleShape)
                .size(PUCK_SIZE)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val indicatorModifier = Modifier.size(22.dp)
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = indicatorModifier,
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                CircularProgressIndicator(
                    progress = { (currentFraction() * 0.8f).coerceIn(0f, 0.8f) },
                    modifier = indicatorModifier.graphicsLayer {
                        rotationZ = currentFraction().coerceIn(0f, 2f) * 180f
                    },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                )
            }
        }
    }
}

private val PUCK_SIZE = 40.dp

/** How far below the bar's edge the puck rests while a refresh runs. */
private val PUCK_REST = 16.dp

/** Headroom for the damped travel past the threshold. */
private val PUCK_OVERSHOOT = 20.dp

/**
 * The account photo's diameter.
 *
 * Smaller than an icon's 24dp box: a filled circle carries more weight than a
 * glyph does, and at 24 it sat heavier in the bar than the wordmark opposite it.
 */
private val AVATAR_SIZE = 28.dp
