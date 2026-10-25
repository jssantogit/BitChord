package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

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
    CanonicalRecordingFeedbackPanel(
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
    )
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
) {
    var selected by remember(rating) { mutableDoubleStateOf(rating?.takeIf { it in 1.0..5.0 } ?: 0.0) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(Res.string.recording_feedback_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The icons ARE the score: no duplicate "3.5 / 5" underneath.
                // Keep the selected numeric value for screen readers only.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "${selected} stars selected" },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(5) { i ->
                        val index = i.toDouble()
                        Box(
                            modifier = Modifier.size(width = 42.dp, height = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = when {
                                    selected >= index + 1.0 -> Icons.Rounded.Star
                                    selected >= index + 0.5 -> Icons.Rounded.StarHalf
                                    else -> Icons.Rounded.StarBorder
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(38.dp),
                            )
                            Row(Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !saving,
                                            onClick = { selected = (index + 0.5).coerceAtLeast(1.0) },
                                        )
                                        .semantics {
                                            contentDescription = "${(index + 0.5).coerceAtLeast(1.0)} stars"
                                        },
                                )
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            enabled = !saving,
                                            onClick = { selected = index + 1.0 },
                                        )
                                        .semantics {
                                            contentDescription = "${i + 1} stars"
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selected) },
                enabled = selected in 1.0..5.0 && !saving,
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else Text(stringResource(Res.string.recording_feedback_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
