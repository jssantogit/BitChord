package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.RatingDisplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Icon
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.ui.theme.appleMusicKeyColor
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.data.listening.CanonicalRatedLibrarySnapshot
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.my_ratings_empty
import com.music.bitchord.sharedui.resources.my_ratings_error
import com.music.bitchord.sharedui.resources.my_ratings_feed_loading
import com.music.bitchord.sharedui.resources.my_ratings_more
import com.music.bitchord.sharedui.resources.my_ratings_title
import com.music.bitchord.sharedui.resources.my_ratings_remote_source
import com.music.bitchord.sharedui.resources.my_ratings_local_source
import com.music.bitchord.sharedui.resources.my_ratings_remote_rating
import com.music.bitchord.sharedui.resources.my_ratings_remote_starred
import com.music.bitchord.sharedui.resources.my_ratings_remote_cleared
import com.music.bitchord.sharedui.resources.my_ratings_conflict_local
import com.music.bitchord.sharedui.resources.my_ratings_conflict_remote
import com.music.bitchord.sharedui.resources.my_ratings_remote_loading
import com.music.bitchord.sharedui.resources.my_ratings_remote_unavailable
import com.music.bitchord.sharedui.resources.my_ratings_remote_error
import com.music.bitchord.sharedui.resources.my_ratings_remote_partial
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

/**
 * Compact native action for personal ratings; the geometry is a BitChord
 * adaptation, not a measurement of Apple Music's rating editor.
 */
@Composable
internal fun MyRatingsInlineAction(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    subdued: Boolean = false,
) {
    val ink = if (subdued) MaterialTheme.colorScheme.onSurfaceVariant
        else appleMusicKeyColor()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("my-ratings-inline-action"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = ink.copy(alpha = if (enabled) 1f else 0.4f),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** Library entry works independently of YouTube login and Rediscovery eligibility. */
@Composable
internal fun CanonicalMyRatingsEntry(onOpen: () -> Unit) {
    MyRatingsInlineAction(
        label = stringResource(Res.string.my_ratings_title),
        onClick = onOpen,
    )
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
    val remoteState = rememberRemoteRatingsState()
    val diagnosticTruth = remember(state, remoteState) {
        MyRatingsRenderEvidence.from(state, remoteState).metadata()
    }
    LaunchedEffect(diagnosticTruth) {
        try {
            Diagnostics.event(
                category = DiagnosticCategory.UI,
                name = "my_ratings_content_state_v1",
                metadata = diagnosticTruth,
            )
        } catch (_: Exception) {
            // Diagnostics are always fail-open and never block a visible rating.
        }
    }
    CanonicalMyRatingsContent(
        state = state,
        remoteState = remoteState,
        onBack = onBack,
        onEdit = { id -> editingId = id },
    )
    editingId?.let { canonicalId ->
        CanonicalRecordingRatingDialog(
            recordingId = canonicalId,
            onDismiss = { editingId = null },
        )
    }
}

/** Pure state rendering: testable without a real provider, account or Room singleton. */
@Composable
internal fun CanonicalMyRatingsContent(
    state: MyRatingsUiState,
    remoteState: RemoteRatingsUiState = RemoteRatingsUiState.Disabled,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val remoteRows = (remoteState as? RemoteRatingsUiState.Ready)?.rows.orEmpty()
    val remoteByRecording = remoteRows.associateBy { it.recordingId }
    val localIds = (state as? MyRatingsUiState.Content)?.items.orEmpty()
        .mapTo(mutableSetOf()) { it.recordingId }
    val remoteOnlyRows = remoteRows.filter { row ->
        row.recordingId !in localIds &&
            row.evidence.any { it.numericValue != null || it.starred }
    }
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
                MyRatingsInlineAction(
                    label = stringResource(Res.string.rediscovery_close),
                    onClick = onBack,
                    subdued = true,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                when (state) {
                    MyRatingsUiState.Loading -> item(key = "loading") {
                        NativeLoadingSpinner()
                    }
                    MyRatingsUiState.Empty -> if (remoteOnlyRows.isEmpty()) item(key = "empty") {
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
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            rating.title ?: stringResource(Res.string.rediscovery_unavailable),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        rating.artistDisplay?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                    }
                                    MyRatingTrailingStars(
                                        localValue = rating.value,
                                        remote = remoteByRecording[rating.recordingId],
                                    )
                                }
                                remoteByRecording[rating.recordingId]?.let { remote ->
                                    RemoteRatingDetails(remote, localValue = rating.value)
                                }
                                if (rating.title != null) {
                                    MyRatingsInlineAction(
                                        label = stringResource(Res.string.recording_feedback_edit),
                                        icon = PlaylistEditorIcons.Ratings,
                                        onClick = { onEdit(rating.recordingId) },
                                    )
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
                if (remoteOnlyRows.isNotEmpty()) {
                    items(remoteOnlyRows, key = { "remote-" + it.recordingId }) { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.title ?: stringResource(Res.string.rediscovery_unavailable),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    row.artistDisplay?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                                MyRatingTrailingStars(localValue = null, remote = row)
                            }
                            RemoteRatingDetails(row, localValue = null)
                            // Remote evidence is read-only; this edits a separate LOCAL
                            // score for the same verified canonical RecordingId.
                            if (row.title != null) {
                                MyRatingsInlineAction(
                                    label = stringResource(Res.string.recording_feedback_edit),
                                    icon = PlaylistEditorIcons.Ratings,
                                    onClick = { onEdit(row.recordingId) },
                                )
                            }
                        }
                    }
                }
                when (remoteState) {
                    RemoteRatingsUiState.Disabled -> Unit
                    RemoteRatingsUiState.Loading -> item(key = "remote-loading") {
                        NativeLoadingSpinner()
                    }
                    RemoteRatingsUiState.Unavailable -> item(key = "remote-unavailable") {
                        Text(stringResource(Res.string.my_ratings_remote_unavailable), Modifier.padding(16.dp))
                    }
                    RemoteRatingsUiState.Error -> item(key = "remote-error") {
                        Text(
                            stringResource(Res.string.my_ratings_remote_error),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is RemoteRatingsUiState.Ready -> {
                        if (remoteState.coverage.unmatched > 0) {
                            item(key = "remote-partial") {
                                Text(
                                    stringResource(Res.string.my_ratings_remote_partial),
                                    Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fixed right-side star strip(s), keeping raw local and remote evidence
 * separate when different. Identical remote/local scores display only once.
 * Pro-venance and numeric scores remain in the star accessibility semantics.
 */
@Composable
private fun MyRatingTrailingStars(localValue: Double?, remote: RemoteRatingUiRow?) {
    val numericValues = remote?.evidence?.mapNotNull { it.numericValue }?.distinct().orEmpty()
    Column(
        modifier = Modifier.padding(start = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.End,
    ) {
        if (numericValues.size == 1) {
            CanonicalRatingStars(RatingDisplay(localValue = localValue, remoteValue = numericValues.single()))
        } else {
            localValue?.let { CanonicalRatingStars(RatingDisplay(localValue = it)) }
            numericValues.forEach { CanonicalRatingStars(RatingDisplay(remoteValue = it)) }
        }
    }
}

/** Remote evidence is descriptive and never an editor, an inferred score or a mutation. */
@Composable
private fun RemoteRatingDetails(row: RemoteRatingUiRow, localValue: Double?) {
    val numericValues = row.evidence.mapNotNull { it.numericValue }
    if (row.evidence.any { it.starred }) {
        Text(stringResource(Res.string.my_ratings_remote_starred))
    }
    if (row.evidence.any { it.cleared }) {
        Text(stringResource(Res.string.my_ratings_remote_cleared))
    }
    if (numericValues.distinct().size > 1) {
        Text(
            stringResource(Res.string.my_ratings_conflict_remote),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (localValue != null && numericValues.any { it.toDouble() != localValue }) {
        Text(
            stringResource(Res.string.my_ratings_conflict_local),
            color = MaterialTheme.colorScheme.error,
        )
    }
}
