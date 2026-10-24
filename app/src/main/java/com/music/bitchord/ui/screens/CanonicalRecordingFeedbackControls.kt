package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.listening.CanonicalRecordingFeedbackEditor
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RecordingRatingStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.recording_feedback_clear
import com.music.bitchord.sharedui.resources.recording_feedback_current
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import com.music.bitchord.sharedui.resources.recording_feedback_history
import com.music.bitchord.sharedui.resources.recording_feedback_unavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * L4: the destination itself remains read-only. Only an explicit tap on one
 * numeric option or Clear invokes a rating command. No MediaController, queue,
 * provider ID, player callback, network, or playback event is involved.
 */
@Composable
internal fun CanonicalRecordingFeedbackEntry(recordingId: String) {
    val context = LocalContext.current.applicationContext
    val store: RecordingRatingStore = remember(context) {
        RoomRecordingRatingStore(ListeningEventDatabase.get(context).recordingRatingDao())
    }
    val editor = remember(context, store) {
        CanonicalRecordingFeedbackEditor(store) { id ->
            val app = context.applicationContext
            val available = CanonicalLibraryAvailabilityReaders.persistent(app)
                .availableRecordingIds().contains(id)
            available && CanonicalCatalogDatabase.get(app).catalogDao().recordingById(id) != null
        }
    }
    val observable = remember(store, recordingId) { store.observeRating(recordingId) }
    val current by observable.collectAsState(initial = null)
    val historyCount by produceState(0, store, recordingId, current) {
        value = try {
            withContext(Dispatchers.IO) { store.ratingHistory(recordingId).size }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
    }
    var denied by remember(recordingId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CanonicalRecordingFeedbackPanel(
        rating = current?.value,
        historyCount = historyCount,
        onSetRating = { value ->
            scope.launch {
                denied = try {
                    !withContext(Dispatchers.IO) { editor.set(recordingId, value) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
            }
        },
        onClear = {
            scope.launch {
                denied = try {
                    !withContext(Dispatchers.IO) { editor.clear(recordingId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
            }
        },
    )
    if (denied) {
        Text(
            text = stringResource(Res.string.recording_feedback_unavailable),
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun CanonicalRecordingFeedbackPanel(
    rating: Double?,
    onSetRating: (Double) -> Unit,
    onClear: () -> Unit,
    historyCount: Int = 0,
) {
    var editing by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(Res.string.recording_feedback_current, rating?.toString() ?: "—"),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { editing = !editing }) {
            Text(stringResource(Res.string.recording_feedback_edit))
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf(5.0, 4.5, 4.0, 3.0, 2.0).forEach { value ->
                    TextButton(onClick = {
                        onSetRating(value)
                        editing = false
                    }) { Text(value.toString()) }
                }
            }
            if (rating != null) {
                TextButton(onClick = {
                    onClear()
                    editing = false
                }) { Text(stringResource(Res.string.recording_feedback_clear)) }
            }
            Text(
                text = stringResource(Res.string.recording_feedback_history, historyCount),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
