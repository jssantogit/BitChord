package com.music.bitchord.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.music.bitchord.ui.components.ActionRow
import com.music.bitchord.ui.components.NativeAlertHost
import com.music.bitchord.ui.components.NativeStatusAlert
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import com.music.bitchord.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.bitchord.ui.components.AppleRatingStarGlyph
import com.music.bitchord.ui.components.appleRatingStarFill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.music.bitchord.ui.components.NativeContextActionPlate
import com.music.bitchord.ui.components.NativeActionCardTreatment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.listening.CanonicalRecordingFeedbackEditor
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RecordingRatingStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import com.music.bitchord.playback.androidCanonicalQueueResolver
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cancel
import com.music.bitchord.sharedui.resources.recording_feedback_dialog_save
import com.music.bitchord.sharedui.resources.recording_feedback_dialog_title
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import com.music.bitchord.sharedui.resources.recording_feedback_unavailable
import com.music.bitchord.sharedui.resources.recording_feedback_stars_selected
import com.music.bitchord.sharedui.resources.recording_feedback_stars_value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.music.bitchord.ui.components.CompactRatingIndicator
import com.music.bitchord.ui.components.CompactRatingGeometry
import com.music.bitchord.ui.components.CompactRatingStyle
import com.music.bitchord.ui.components.LocalCompactRatingStyle
import com.music.bitchord.ui.components.compactRatingColorStops
import com.music.bitchord.ui.components.normalizedCompactRating

/**
 * Both song actions and My Ratings use this *same* local rating dialog.
 * No provider rating is writable; the editor checks canonical availability
 * against Catalog immediately before changing first-party Listening Room.
 */
@Composable
internal fun CanonicalRecordingFeedbackEntry(recordingId: String) {
    var editing by remember(recordingId) { mutableStateOf(false) }
    CanonicalRecordingFeedbackAction(onClick = { editing = true })
    if (editing) {
        CanonicalRecordingRatingDialog(
            recordingId = recordingId,
            onDismiss = { editing = false },
        )
    }
}

/**
 * Reuse the action sheet's exact layout contract (20/22dp icon, 18dp gap,
 * bodyLarge text, row height and alignment). A TextButton here had its own
 * typography and paddings and looked out of place beside Radio/Queue actions.
 */
@Composable
internal fun CanonicalRecordingFeedbackAction(onClick: () -> Unit) {
    ActionRow(
        icon = ratingActionIcon(),
        label = stringResource(Res.string.recording_feedback_edit),
        onClick = onClick,
    )
}

/** One icon identity across the expanded player, rating picker and action menus. */
internal fun ratingActionIcon() = PlaylistEditorIcons.RatingPlain

/** The player never guesses a provider-native ID or track title for a local rating. */
private data class RatingTargetResolution(val pending: Boolean, val recordingId: String?)

/** A player Song is NOT a RecordingId: verify the actual rendition before editing. */
@Composable
internal fun CanonicalSongRatingDialog(
    song: Song,
    onDismiss: () -> Unit,
    anchorBounds: IntRect? = null,
    highlightPlayerLauncher: Boolean = false,
    artworkUrl: String? = song.thumbnailUrl,
) {
    val context = LocalContext.current.applicationContext
    // The account-scoped Catalog asset snapshot is an offline, verified identity
    // proof. Unrated local files and cached canonical Navidrome songs can be
    // edited with no provider lookup. Resolver remains a strict fallback.
    val cachedId = LocalCanonicalRatingDisplay.current.recordingIdForSong(song)
    val resolution by produceState(
        initialValue = RatingTargetResolution(pending = cachedId == null, recordingId = cachedId),
        key1 = context,
        key2 = song,
        key3 = cachedId,
    ) {
        if (cachedId != null) {
            value = RatingTargetResolution(false, cachedId)
            return@produceState
        }
        val resolvedId = try {
            withContext(Dispatchers.IO) {
                CanonicalSongFeedbackTarget(androidCanonicalQueueResolver(context)).recordingId(song)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // no heuristic matching by provider ID, title or artist
        }
        value = RatingTargetResolution(false, resolvedId)
    }
    val id = resolution.recordingId
    if (id != null) {
        CanonicalRecordingRatingDialog(
            recordingId = id, onDismiss = onDismiss, anchorBounds = anchorBounds,
            highlightPlayerLauncher = highlightPlayerLauncher,
            artworkUrl = artworkUrl,
        )
    } else {
        NativeAlertHost(onDismiss = onDismiss) {
            NativeStatusAlert(
                hazeState = null,
                title = stringResource(Res.string.recording_feedback_dialog_title),
                description = stringResource(Res.string.recording_feedback_unavailable),
                loading = resolution.pending,
                dismissLabel = stringResource(Res.string.cancel),
                onDismiss = onDismiss,
            )
        }
    }
}

/** The only mutation host for rating edits. A canceled or unconfirmed selection never writes. */
@Composable
internal fun CanonicalRecordingRatingDialog(
    recordingId: String,
    onDismiss: () -> Unit,
    anchorBounds: IntRect? = null,
    highlightPlayerLauncher: Boolean = false,
    artworkUrl: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val store: RecordingRatingStore = remember(context) {
        RoomRecordingRatingStore(ListeningEventDatabase.get(context).recordingRatingDao())
    }
    val editor = remember(context, store) {
        CanonicalRecordingFeedbackEditor(store) { id ->
            val app = context.applicationContext
            CanonicalLibraryAvailabilityReaders.persistent(app).availableRecordingIds().contains(id) &&
                CanonicalCatalogDatabase.get(app).catalogDao().recordingById(id) != null
        }
    }
    val observable = remember(store, recordingId) { store.observeRating(recordingId) }
    val current by observable.collectAsState(initial = null)
    var saving by remember(recordingId) { mutableStateOf(false) }
    var denied by remember(recordingId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    key(recordingId) { CanonicalRecordingFeedbackPanel(
        rating = current?.value,
        anchorBounds = anchorBounds,
        highlightPlayerLauncher = highlightPlayerLauncher,
        artworkUrl = artworkUrl,
        saving = saving,
        denied = denied,
        onDismiss = { if (!saving) onDismiss() },
        onSave = { value ->
            if (!saving) {
                saving = true
                denied = false
                scope.launch {
                    val succeeded = try {
                        withContext(Dispatchers.IO) {
                            if (value == 0.0) editor.clear(recordingId)
                            else editor.set(recordingId, value)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    saving = false
                    if (succeeded) onDismiss() else denied = true
                }
            }
        },
    ) }
}

/**
 * Only an explicit, valid selection gets one star's restrained pulse.
 * Loading a persisted rating never pulses; Reduce animations stays static.
 */
internal fun ratingSelectionPulseIndex(selected: Double, touched: Boolean, reducedMotion: Boolean): Int? =
    if (!touched || reducedMotion || !selected.isFinite() || selected !in 1.0..5.0) null
    else ceil(selected).toInt() - 1

/** Dragging outside the first star clears the whole local rating. */
internal fun ratingSelectionFromDrag(positionX: Float, width: Float): Double {
    if (!positionX.isFinite() || !width.isFinite() || width <= 0f) return 0.0
    val halfSteps = (positionX.coerceIn(0f, width) / width * 10f).roundToInt()
    return if (halfSteps == 0) 0.0 else (halfSteps / 2.0).coerceAtLeast(1.0)
}

internal fun lastRatedStarIndex(selected: Double): Int =
    if (selected.isFinite() && selected in 1.0..5.0) ceil(selected).toInt() - 1 else -1

/** In numeric style only the terminal filled star carries the V3 digit.
 * The preceding filled stars are plain white silhouettes. */
internal fun ratingEditorUsesNumericGlyph(
    style: CompactRatingStyle,
    selected: Double,
    slot: Int,
): Boolean = style == CompactRatingStyle.NUMERIC && slot == lastRatedStarIndex(selected)
    && slot >= 0

/** Numeric rating never uses the color scale: every star is theme ink.
 * Fractional color ratings carry both neighboring hues within each filled
 * star; solid integer ratings keep their single explicit color.
 */
internal fun ratingEditorGradientStops(
    selected: Double,
    style: CompactRatingStyle,
): List<Pair<Float, Color>> =
    if (style == CompactRatingStyle.COLOR && normalizedCompactRating(selected) != null)
        compactRatingColorStops(selected)
    else emptyList()

internal fun ratingEditorStarTint(
    style: CompactRatingStyle,
    selected: Double,
    filled: Boolean,
    neutral: Color,
): Color = if (style == CompactRatingStyle.NUMERIC || !filled) neutral
else compactRatingColorStops(selected).firstOrNull()?.second ?: neutral

/** Fixed solid background matching the player's light/dark base layer. */
internal fun ratingSheetSolidColor(dark: Boolean): androidx.compose.ui.graphics.Color =
    if (dark) androidx.compose.ui.graphics.Color.Black
    else androidx.compose.ui.graphics.Color.White

internal data class RatingDialogEntranceFrame(val alpha: Float, val scale: Float)
internal fun ratingDialogEntranceFrame(progress: Float, reducedMotion: Boolean): RatingDialogEntranceFrame {
    val p = if (reducedMotion || !progress.isFinite()) 1f else progress.coerceIn(0f, 1f)
    return RatingDialogEntranceFrame(alpha = 0.96f + 0.04f * p, scale = 0.97f + 0.03f * p)
}

/** Every rating entry shares one centered modal, including the player. */
internal fun ratingEditorFloatingMaxWidthDp(): Int = 320

internal fun ratingEditorModalWidthDp(screenWidthDp: Int): Int =
    ((screenWidthDp.takeIf { it > 0 } ?: 320) - 32)
        .coerceIn(1, ratingEditorFloatingMaxWidthDp())

internal fun ratingEditorModalGlyphSizeDp(screenWidthDp: Int): Int =
    ((ratingEditorModalWidthDp(screenWidthDp) - 24) / 5).coerceIn(18, 32)

/** Legacy geometry helper remains for callers, but no modal is anchored. */
internal fun anchoredRatingCardWidthDp(screenWidthDp: Int): Int =
    ((screenWidthDp.takeIf { it > 0 } ?: 320) - 32).coerceIn(1, 300)

/**
 * Icon-based, locally editable 1–5 star modal. Each star has a left (half)
 * and right (whole) touch target; every increment is explicit and accessible.
 * Neither opening nor changing selection persists anything until Save.
 * Dragging left of the first star selects zero and removes a persisted rating.
 */
@Composable
internal fun CanonicalRecordingFeedbackPanel(
    rating: Double?,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
    saving: Boolean = false,
    denied: Boolean = false,
    title: String? = null,
    /** Retained for existing callers; all rating entrypoints now use one centered modal. */
    anchorBounds: IntRect? = null,
    /** No duplicated launcher highlight while the centered modal is open. */
    highlightPlayerLauncher: Boolean = false,
    /** Retained for existing callers; artwork cannot tint the neutral modal. */
    artworkUrl: String? = null,
) {
    var selected by remember { mutableDoubleStateOf(rating?.takeIf { it in 1.0..5.0 } ?: 0.0) }
    var touched by remember { mutableStateOf(false) }
    // Room may deliver the persisted rating after the dialog opens. Hydrate
    // untouched selections, but never erase the user's pending choice.
    LaunchedEffect(rating) {
        if (!touched) selected = rating?.takeIf { it.isFinite() && it in 1.0..5.0 } ?: 0.0
    }
    val selectedDescription = stringResource(Res.string.recording_feedback_stars_selected, selected.toString())
    val reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current
    val pulseIndex = ratingSelectionPulseIndex(selected, touched, reduceMotion)
    val starStyle = LocalCompactRatingStyle.current
    val numericStars = starStyle == CompactRatingStyle.NUMERIC
    val colorStops = ratingEditorGradientStops(selected, starStyle)
    // This editor never persists a draft until Save. Cancel and Back
    // discard the pending selection immediately, including an edited rating.
    val original = rating?.takeIf { it.isFinite() && it in 1.0..5.0 } ?: 0.0
    val requestDismiss: () -> Unit = { if (!saving) onDismiss() }
    // The dialog previously appeared in one frame. Entrance affects only
    // its drawing, never star hit targets, the save path, or the dialog window.
    val dialogEntrance = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) dialogEntrance.snapTo(1f)
        else dialogEntrance.animateTo(
            1f,
            tween(durationMillis = 180, easing = FastOutSlowInEasing),
        )
    }
    // Player, artist, album, playlist and recording all share ONE centered
    // modal. The launcher coordinates and artwork never influence its card.
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetForeground = if (darkTheme) Color.White else Color.Black
    val sheetTitle = title ?: stringResource(Res.string.recording_feedback_dialog_title)
    val modalWidthDp = ratingEditorModalWidthDp(LocalConfiguration.current.screenWidthDp)
    val modalWidth = modalWidthDp.dp
    // Keep the familiar large stars wherever five slots safely fit. Only
    // narrow multi-window layouts reduce their size.
    val modalGlyphSize = ratingEditorModalGlyphSizeDp(LocalConfiguration.current.screenWidthDp).dp
    val ratingEditorContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(
                    // Wide touch cells; smaller visible star vectors.
                    start = 14.dp,
                    end = 14.dp,
                    top = 24.dp,
                    bottom = 18.dp,
                )
                .testTag("native-rating-modal-content")
                .semantics { paneTitle = sheetTitle }
                // Animation affects only the centered card, never star hit targets.
                .graphicsLayer {
                    val entrance = ratingDialogEntranceFrame(dialogEntrance.value, reduceMotion)
                    alpha = entrance.alpha
                    scaleX = entrance.scale
                    scaleY = entrance.scale
                    transformOrigin = TransformOrigin.Center
                },
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.recording_feedback_dialog_title),
                modifier = Modifier.fillMaxWidth().testTag("native-rating-modal-title"),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = sheetForeground,
                textAlign = TextAlign.Center,
            )
            Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("native-rating-editor-star-row")
                        .semantics { contentDescription = selectedDescription }
                        .pointerInput(saving) {
                            detectDragGestures(onDrag = { change, _ ->
                                if (!saving) {
                                    change.consume()
                                    touched = true
                                    selected = ratingSelectionFromDrag(
                                        change.position.x, size.width.toFloat(),
                                    )
                                }
                            })
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(5) { i ->
                        val index = i.toDouble()
                        val starPulse = remember { Animatable(1f) }
                        // Re-trigger for half→whole on the same star, not
                        // only when the chosen star index changes.
                        LaunchedEffect(selected, touched, reduceMotion) {
                            if (pulseIndex == i && !reduceMotion) {
                                starPulse.snapTo(0.88f)
                                starPulse.animateTo(
                                    1f,
                                    spring(
                                        dampingRatio = 0.70f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            } else {
                                starPulse.snapTo(1f)
                            }
                        }
                        val half = (index + 0.5).coerceAtLeast(1.0)
                        val halfDescription = stringResource(Res.string.recording_feedback_stars_value, half.toString())
                        val wholeDescription = stringResource(Res.string.recording_feedback_stars_value, (index + 1.0).toString().removeSuffix(".0"))
                        val starFill = appleRatingStarFill(selected, i)
                        Box(
                            modifier = Modifier.weight(1f).height(56.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Retain the player's exact original star
                            // contours but drop the circular *button* platter.
                            // Five repeated platters looked like generic chips.
                            // A 32dp vector viewport matches the reference and
                            // preserves full-height, accessible touch slots.
                            Box(
                                modifier = Modifier.size(modalGlyphSize)
                                    .testTag("rating-editor-star-$i-${starFill.name.lowercase()}")
                                    .graphicsLayer {
                                        scaleX = starPulse.value
                                        scaleY = starPulse.value
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                val filled = starFill != com.music.bitchord.ui.components.AppleRatingStarFill.EMPTY
                                // A rating is one score. Never color the first
                                // four stars as scores 1, 2, 3, 4 independently.
                                val slotStops = if (filled && !numericStars) colorStops else emptyList()
                                val slotBrush = if (slotStops.map { it.second }.distinct().size > 1)
                                    Brush.linearGradient(*slotStops.toTypedArray()) else null
                                val glyphSize = modalGlyphSize
                                if (ratingEditorUsesNumericGlyph(starStyle, selected, i)) {
                                    // Only the last selected star is numbered.
                                    CompactRatingIndicator(
                                        value = selected,
                                        geometry = CompactRatingGeometry.STANDALONE,
                                        size = glyphSize,
                                    )
                                } else {
                                    AppleRatingStarGlyph(
                                        fill = starFill,
                                        standalone = true,
                                        tint = ratingEditorStarTint(
                                            starStyle, selected, filled, sheetForeground,
                                        ),
                                        colorBrush = slotBrush,
                                        outlineTint = sheetForeground,
                                        modifier = Modifier.size(glyphSize),
                                    )
                                }
                            }
                            Row(Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !saving,
                                            onClick = { touched = true; selected = half },
                                        )
                                        .semantics {
                                            contentDescription = halfDescription
                                        },
                                )
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !saving,
                                            onClick = { touched = true; selected = index + 1.0 },
                                        )
                                        .semantics {
                                            contentDescription = wholeDescription
                                        },
                                )
                            }
                        }
                    }
                }
            if (denied) {
                Text(
                    stringResource(Res.string.recording_feedback_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NativeRatingEditorAction(
                    label = stringResource(Res.string.cancel),
                    onClick = requestDismiss,
                    enabled = !saving,
                    emphasized = false,
                    darkTheme = darkTheme,
                    modifier = Modifier.weight(1f),
                    tag = "rating-editor-cancel-action",
                )
                NativeRatingEditorAction(
                    label = if (selected == 0.0) "Remover avaliação"
                        else stringResource(Res.string.recording_feedback_dialog_save),
                    onClick = { onSave(selected) },
                    enabled = (selected in 1.0..5.0 || (selected == 0.0 && original != 0.0)) && !saving,
                    emphasized = true,
                    darkTheme = darkTheme,
                    loading = saving,
                    modifier = Modifier.weight(1f),
                    tag = "rating-editor-save-action",
                )
            }
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = requestDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            NativeContextActionPlate(
                modifier = Modifier.width(modalWidth)
                    .testTag("native-rating-modal"),
                treatment = NativeActionCardTreatment.NEUTRAL_FROSTED,
                // Never feed album art into the rating dialog's surface.
                artworkUrl = null,
            ) {
                ratingEditorContent()
            }
        }
    }
}
