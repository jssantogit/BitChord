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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.bitchord.ui.components.AppleRatingStarGlyph
import com.music.bitchord.ui.components.appleRatingStarFill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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
        icon = Icons.Rounded.Star,
        label = stringResource(Res.string.recording_feedback_edit),
        onClick = onClick,
    )
}

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
    // Room may deliver the persisted rating after the dialog opens. Hydrate
    // untouched selections, but never erase the user's pending choice.
    LaunchedEffect(rating) {
        if (!touched) selected = rating?.takeIf { it.isFinite() && it in 1.0..5.0 } ?: 0.0
    }
    val selectedDescription = stringResource(Res.string.recording_feedback_stars_selected, selected.toString())
    val reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current
    val pulseIndex = ratingSelectionPulseIndex(selected, touched, reduceMotion)
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
    // The new-surface-patterns recipe treats this as a BitChord choice/editor:
    // one canonical sheet-like dialog, a labeled selection, explicit actions
    // and untouched persistence. No claim of an Apple rating editor.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SettingsGroupSurface(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = com.music.bitchord.ui.components.PAGE_GUTTER)
                .graphicsLayer {
                    val entrance = ratingDialogEntranceFrame(dialogEntrance.value, reduceMotion)
                    alpha = entrance.alpha
                    scaleX = entrance.scale
                    scaleY = entrance.scale
                },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title ?: stringResource(Res.string.recording_feedback_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                            modifier = Modifier.weight(1f).height(52.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // The expanded player uses the exact same 28-unit
                            // Favorite/Favorited vectors. Half uses a clipped
                            // filled vector, not Material StarHalf or a bitmap.
                            AppleRatingStarGlyph(
                                fill = starFill,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(36.dp)
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
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onDismiss, enabled = !saving,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(stringResource(Res.string.cancel)) }
                    androidx.compose.material3.Button(
                        onClick = { onSave(selected) },
                        enabled = selected in 1.0..5.0 && !saving,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = com.music.bitchord.ui.theme.AccentRed,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    ) {
                        if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        else Text(stringResource(Res.string.recording_feedback_dialog_save))
                    }
                }
            }
        }
    }

}