package com.music.bitchord.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.music.bitchord.ui.components.ConfirmationAlert
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import com.music.bitchord.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.bitchord.ui.components.AppleRatingStarGlyph
import com.music.bitchord.ui.components.appleRatingStarFill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
internal fun ratingActionIcon() = PlaylistEditorIcons.Ratings

/** The player never guesses a provider-native ID or track title for a local rating. */
private data class RatingTargetResolution(val pending: Boolean, val recordingId: String?)

/** A player Song is NOT a RecordingId: verify the actual rendition before editing. */
@Composable
internal fun CanonicalSongRatingDialog(song: Song, onDismiss: () -> Unit) {
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
        CanonicalRecordingRatingDialog(recordingId = id, onDismiss = onDismiss)
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.recording_feedback_dialog_title)) },
            text = {
                if (resolution.pending) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(Res.string.recording_feedback_unavailable))
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/** The only mutation host for rating edits. A canceled or unconfirmed selection never writes. */
@Composable
internal fun CanonicalRecordingRatingDialog(recordingId: String, onDismiss: () -> Unit) {
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
        saving = saving,
        denied = denied,
        onDismiss = { if (!saving) onDismiss() },
        onSave = { value ->
            if (!saving) {
                saving = true
                denied = false
                scope.launch {
                    val succeeded = try {
                        withContext(Dispatchers.IO) { editor.set(recordingId, value) }
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

/** Fixed solid background matching the player's light/dark base layer. */
internal fun ratingSheetSolidColor(dark: Boolean): androidx.compose.ui.graphics.Color =
    if (dark) androidx.compose.ui.graphics.Color.Black
    else androidx.compose.ui.graphics.Color.White

internal data class RatingDialogEntranceFrame(val alpha: Float, val scale: Float)
internal fun ratingDialogEntranceFrame(progress: Float, reducedMotion: Boolean): RatingDialogEntranceFrame {
    val p = if (reducedMotion || !progress.isFinite()) 1f else progress.coerceIn(0f, 1f)
    return RatingDialogEntranceFrame(alpha = 0.96f + 0.04f * p, scale = 0.97f + 0.03f * p)
}

/**
 * Icon-based, locally editable 1–5 star modal. Each star has a left (half)
 * and right (whole) touch target; every increment is explicit and accessible.
 * Neither opening nor changing selection persists anything until Save.
 * Deliberately NO clear/reset rating action.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CanonicalRecordingFeedbackPanel(
    rating: Double?,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
    saving: Boolean = false,
    denied: Boolean = false,
    title: String? = null,
) {
    var selected by remember { mutableDoubleStateOf(rating?.takeIf { it in 1.0..5.0 } ?: 0.0) }
    var touched by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Room may deliver the persisted rating after the dialog opens. Hydrate
    // untouched selections, but never erase the user's pending choice.
    LaunchedEffect(rating) {
        if (!touched) selected = rating?.takeIf { it.isFinite() && it in 1.0..5.0 } ?: 0.0
    }
    val selectedDescription = stringResource(Res.string.recording_feedback_stars_selected, selected.toString())
    val reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current
    val pulseIndex = ratingSelectionPulseIndex(selected, touched, reduceMotion)
    // Dismissing an edited value must not silently throw it away. A late Room
    // emission cannot erase the pending selection, and saving stays modal.
    val original = rating?.takeIf { it.isFinite() && it in 1.0..5.0 } ?: 0.0
    val requestDismiss: () -> Unit = {
        if (!saving) {
            if (touched && selected != original) confirmDiscard = true
            else onDismiss()
        }
    }
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
    // Rating is a contextual bottom sheet, not a Settings card or a centered
    // alert. Follow the user's marked composition: stars and two equal actions,
    // no artwork, text summary, visible title or drag handle. The pane title
    // remains exposed to accessibility services.
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetBackground = ratingSheetSolidColor(darkTheme)
    val sheetForeground = if (darkTheme) androidx.compose.ui.graphics.Color.White
        else androidx.compose.ui.graphics.Color.Black
    // AM-DECOMP's key tint; do not infer a native rating sheet from this token.
    val sheetAccent = if (darkTheme) androidx.compose.ui.graphics.Color(0xFF0091FF)
        else androidx.compose.ui.graphics.Color(0xFF0088FF)
    val sheetTitle = title ?: stringResource(Res.string.recording_feedback_dialog_title)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = sheetBackground,
        contentColor = sheetForeground,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.48f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 20.dp)
                .testTag("native-rating-bottom-sheet")
                .semantics { paneTitle = sheetTitle }
                .graphicsLayer {
                    val entrance = ratingDialogEntranceFrame(dialogEntrance.value, reduceMotion)
                    alpha = entrance.alpha
                    scaleX = entrance.scale
                    scaleY = entrance.scale
                },
            verticalArrangement = Arrangement.spacedBy(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("native-rating-editor-star-row")
                        .semantics { contentDescription = selectedDescription },
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
                            // A 48dp glyph viewport keeps the visible star
                            // near 28dp while maintaining generous touch slots.
                            AppleRatingStarGlyph(
                                fill = starFill,
                                standalone = true,
                                tint = if (starFill == com.music.bitchord.ui.components.AppleRatingStarFill.EMPTY)
                                    sheetForeground else sheetAccent,
                                modifier = Modifier
                                    .size(56.dp)
                                    .testTag("rating-editor-star-$i-${starFill.name.lowercase()}")
                                    .graphicsLayer {
                                        scaleX = starPulse.value
                                        scaleY = starPulse.value
                                    },
                            )
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Button(
                    onClick = requestDismiss,
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(52.dp)
                        .testTag("rating-editor-cancel-action"),
                    shape = RoundedCornerShape(26.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (darkTheme)
                            androidx.compose.ui.graphics.Color(0xFF2B2B2D)
                        else androidx.compose.ui.graphics.Color(0xFFF0F0F2),
                        contentColor = sheetForeground,
                    ),
                ) {
                    Text(
                        stringResource(Res.string.cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
                androidx.compose.material3.Button(
                    onClick = { onSave(selected) },
                    enabled = selected in 1.0..5.0 && !saving,
                    modifier = Modifier.weight(1f).height(52.dp)
                        .testTag("rating-editor-save-action"),
                    shape = RoundedCornerShape(26.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = sheetAccent,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        disabledContainerColor = if (darkTheme)
                            androidx.compose.ui.graphics.Color(0xFF343437)
                        else androidx.compose.ui.graphics.Color(0xFFDEDEE1),
                        disabledContentColor = sheetForeground.copy(alpha = 0.45f),
                    ),
                ) {
                    if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    else Text(
                        stringResource(Res.string.recording_feedback_dialog_save),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmationAlert(
            hazeState = null,
            title = androidx.compose.ui.res.stringResource(R.string.editor_discard_title),
            description = androidx.compose.ui.res.stringResource(R.string.editor_discard_rating_body),
            confirmLabel = androidx.compose.ui.res.stringResource(R.string.editor_discard_confirm),
            dismissLabel = androidx.compose.ui.res.stringResource(R.string.editor_keep_editing),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onDismiss()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}