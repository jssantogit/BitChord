package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.listening.CanonicalRecordingFeedbackEditor
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RecordingRatingStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.recording_feedback_current
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import com.music.bitchord.sharedui.resources.recording_feedback_unavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Only a confirmed star selection invokes a local rating write. No playback,
 * provider reaction, or track-navigation action can change the rating.
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

/**
 * First-party recording ratings are the only reactions in the Android UI.
 * Keep the existing guarded persistence callback, but do not write until Save.
 * The full #179 dialog will replace this compatibility presentation on merge.
 */
@Composable
internal fun CanonicalRecordingFeedbackPanel(
    rating: Double?,
    onSetRating: (Double) -> Unit,
    @Suppress("UNUSED_PARAMETER") onClear: () -> Unit,
    @Suppress("UNUSED_PARAMETER") historyCount: Int = 0,
) {
    var editing by remember { mutableStateOf(false) }
    var selected by remember(rating, editing) {
        mutableDoubleStateOf(rating?.takeIf { it.isFinite() && it in 1.0..5.0 } ?: 0.0)
    }
    Column(
        modifier = Modifier.fillMaxWidth().wrapContentHeight()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        TextButton(onClick = { editing = !editing }) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(stringResource(Res.string.recording_feedback_edit))
        }
        if (editing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.size(36.dp),
                        )
                        Row(Modifier.matchParentSize()) {
                            listOf((index + 0.5).coerceAtLeast(1.0), index + 1.0).forEach { value ->
                                val label = stringResource(Res.string.recording_feedback_current, value.toString())
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .clickable { selected = value }
                                        .semantics { contentDescription = label },
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { editing = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.cancel))
                }
                TextButton(
                    enabled = selected in 1.0..5.0,
                    onClick = {
                        onSetRating(selected)
                        editing = false
                    },
                ) {
                    Text(androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.save))
                }
            }
        }
    }
}
