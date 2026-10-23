package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.listening.CanonicalRatedLibrarySnapshot
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.my_ratings_empty
import com.music.bitchord.sharedui.resources.my_ratings_error
import com.music.bitchord.sharedui.resources.my_ratings_feed_loading
import com.music.bitchord.sharedui.resources.my_ratings_more
import com.music.bitchord.sharedui.resources.my_ratings_title
import com.music.bitchord.sharedui.resources.recording_feedback_current
import com.music.bitchord.sharedui.resources.recording_feedback_edit
import com.music.bitchord.sharedui.resources.rediscovery_close
import com.music.bitchord.sharedui.resources.rediscovery_unavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Library entry works independently of YouTube login and Rediscovery eligibility. */
@Composable
internal fun CanonicalMyRatingsEntry(onOpen: () -> Unit) {
    TextButton(onClick = onOpen) {
        Text(stringResource(Res.string.my_ratings_title))
    }
}

/** This inset is for the shared floating MainActivity chrome, not a replacement for it.
 * Both full-screen Listening pages previously started their headers at the edge-to-edge
 * window origin, underneath the persistent floating top bar.
 */
@Composable
internal fun ListeningLibraryPage(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 72.dp)
                .navigationBarsPadding(),
        ) {
            content()
        }
    }
}

internal data class MyRatingsRow(
    val recordingId: String,
    val value: Double,
    val title: String?,
    val artistDisplay: String?,
)

internal sealed interface MyRatingsUiState {
    data object Loading : MyRatingsUiState
    data object Empty : MyRatingsUiState
    data object Error : MyRatingsUiState
    data class Content(
        val items: List<MyRatingsRow>,
        val hasMore: Boolean,
    ) : MyRatingsUiState
}

/**
 * Room remains the only source of first-party ratings. A remote provider must not write
 * to recording_rating_current or silently feed recommendation preferences.
 */
@Composable
internal fun CanonicalMyRatingsScreen(
    onBack: () -> Unit,
    ratingFeedOverride: Flow<CanonicalRatedLibrarySnapshot>? = null,
) {
    val context = LocalContext.current.applicationContext
    val feed = remember(context, ratingFeedOverride) {
        try {
            ratingFeedOverride ?: RoomRecordingRatingStore(
                ListeningEventDatabase.get(context).recordingRatingDao(),
            ).observeRatedRecordings(limit = 500)
        } catch (_: Exception) {
            // Database opening can fail before the Flow is subscribed.
            null
        }
    }
    val state by produceState<MyRatingsUiState>(
        initialValue = if (feed == null) MyRatingsUiState.Error else MyRatingsUiState.Loading,
        key1 = feed,
        key2 = context,
    ) {
        if (feed == null) {
            value = MyRatingsUiState.Error
            return@produceState
        }
        try {
            feed.collectLatest { snapshot ->
                if (snapshot.items.isEmpty()) {
                    value = MyRatingsUiState.Empty
                } else {
                    value = MyRatingsUiState.Loading
                    val metadata = try {
                        withContext(Dispatchers.IO) {
                            val dao = CanonicalCatalogDatabase.get(context).catalogDao()
                            snapshot.items.associate { rated ->
                                rated.recordingId to dao.recordingById(rated.recordingId)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A broken/offline Catalog cannot erase a persisted first-party rating.
                        emptyMap()
                    }
                    value = MyRatingsUiState.Content(
                        items = snapshot.items.map { rated ->
                            val record = metadata[rated.recordingId]
                            MyRatingsRow(
                                recordingId = rated.recordingId,
                                value = rated.value,
                                title = record?.title,
                                artistDisplay = record?.artistDisplay,
                            )
                        },
                        hasMore = snapshot.hasMore,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not collapse DAO/Flow failures into an apparently empty library.
            value = MyRatingsUiState.Error
        }
    }
    var editingId by remember { mutableStateOf<String?>(null) }
    CanonicalMyRatingsContent(
        state = state,
        onBack = onBack,
        editingId = editingId,
        onEdit = { id -> editingId = if (editingId == id) null else id },
    )
}

/** Pure state rendering: testable without a real provider, account or Room singleton. */
@Composable
internal fun CanonicalMyRatingsContent(
    state: MyRatingsUiState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    editingId: String? = null,
) {
    ListeningLibraryPage {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(Res.string.my_ratings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(Res.string.rediscovery_close))
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                when (state) {
                    MyRatingsUiState.Loading -> item(key = "loading") {
                        Text(stringResource(Res.string.my_ratings_feed_loading), Modifier.padding(16.dp))
                    }
                    MyRatingsUiState.Empty -> item(key = "empty") {
                        Text(stringResource(Res.string.my_ratings_empty), Modifier.padding(16.dp))
                    }
                    MyRatingsUiState.Error -> item(key = "error") {
                        Text(
                            stringResource(Res.string.my_ratings_error),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is MyRatingsUiState.Content -> {
                        items(state.items, key = { it.recordingId }) { rating ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    rating.title ?: stringResource(Res.string.rediscovery_unavailable),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (rating.artistDisplay != null) {
                                    Text(rating.artistDisplay, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    stringResource(Res.string.recording_feedback_current, rating.value.toString()),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (rating.title != null) {
                                    TextButton(onClick = { onEdit(rating.recordingId) }) {
                                        Text(stringResource(Res.string.recording_feedback_edit))
                                    }
                                    if (editingId == rating.recordingId) {
                                        CanonicalRecordingFeedbackEntry(rating.recordingId)
                                    }
                                }
                            }
                        }
                        if (state.hasMore) {
                            item(key = "overflow") {
                                Text(
                                    stringResource(Res.string.my_ratings_more, state.items.size),
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
