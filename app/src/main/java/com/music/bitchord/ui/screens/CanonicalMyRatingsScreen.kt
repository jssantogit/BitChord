package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.listening.CanonicalRatedLibrarySnapshot
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.my_ratings_empty
import com.music.bitchord.sharedui.resources.my_ratings_loading
import com.music.bitchord.sharedui.resources.my_ratings_more
import com.music.bitchord.sharedui.resources.my_ratings_title
import com.music.bitchord.sharedui.resources.recording_feedback_current
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import com.music.bitchord.sharedui.resources.rediscovery_close
import com.music.bitchord.sharedui.resources.rediscovery_unavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Library entry is available independently of YouTube sign-in and Rediscovery eligibility. */
@Composable
internal fun CanonicalMyRatingsEntry(onOpen: () -> Unit) {
    TextButton(onClick = onOpen) {
        Text(stringResource(Res.string.my_ratings_title))
    }
}

/**
 * L10 read-only list with explicit editor for available canonical Recordings.
 * Unavailable records remain visible with their saved rating, never discarded
 * because a provider went offline or a Catalog record disappeared.
 */
@Composable
internal fun CanonicalMyRatingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        RoomRecordingRatingStore(ListeningEventDatabase.get(context).recordingRatingDao())
    }
    val ratingFeed = remember(store) { store.observeRatedRecordings(limit = 500) }
    val snapshot by ratingFeed.collectAsState(
        initial = CanonicalRatedLibrarySnapshot(emptyList(), false),
    )
    val rows by produceState<Map<String, RecordingEntity?>?>(
        initialValue = null, context, snapshot.items,
    ) {
        value = try {
            withContext(Dispatchers.IO) {
                val dao = CanonicalCatalogDatabase.get(context).catalogDao()
                snapshot.items.associate { rating ->
                    rating.recordingId to dao.recordingById(rating.recordingId)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never invent titles, artist names or aliases when Catalog fails.
            emptyMap()
        }
    }
    var editingId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(Res.string.my_ratings_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = onBack) {
                Text(stringResource(Res.string.rediscovery_close))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (snapshot.items.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(Res.string.my_ratings_empty), Modifier.padding(16.dp))
                }
            }
            items(snapshot.items, key = { it.recordingId }) { rating ->
                val recording = rows?.get(rating.recordingId)
                val ready = rows != null
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = when {
                            !ready -> stringResource(Res.string.my_ratings_loading)
                            recording == null -> stringResource(Res.string.rediscovery_unavailable)
                            else -> recording.title
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (recording != null) {
                        Text(recording.artistDisplay, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(Res.string.recording_feedback_current, rating.value.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (recording != null) {
                        TextButton(onClick = {
                            editingId = if (editingId == rating.recordingId) null else rating.recordingId
                        }) {
                            Text(stringResource(Res.string.recording_feedback_edit))
                        }
                        if (editingId == rating.recordingId) {
                            CanonicalRecordingFeedbackEntry(rating.recordingId)
                        }
                    }
                }
            }
            if (snapshot.hasMore) {
                item(key = "overflow") {
                    Text(
                        stringResource(Res.string.my_ratings_more, snapshot.items.size),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
