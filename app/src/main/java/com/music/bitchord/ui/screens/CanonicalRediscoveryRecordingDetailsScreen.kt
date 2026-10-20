package com.music.bitchord.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.InvalidationTracker
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.rediscovery_close
import com.music.bitchord.sharedui.resources.rediscovery_loading
import com.music.bitchord.sharedui.resources.rediscovery_title
import com.music.bitchord.sharedui.resources.rediscovery_unavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Only RecordingId can identify this destination; no provider ID or playback action. */
internal sealed interface CanonicalRediscoveryRecordingState {
    data object Loading : CanonicalRediscoveryRecordingState
    data object Unavailable : CanonicalRediscoveryRecordingState
    data class Ready(
        val recordingId: String,
        val title: String,
        val artistDisplay: String,
    ) : CanonicalRediscoveryRecordingState
}

private suspend fun loadCanonicalRecordingDetails(
    context: Context,
    recordingId: String,
): CanonicalRediscoveryRecordingState {
    if (recordingId.isBlank()) return CanonicalRediscoveryRecordingState.Unavailable
    val available = CanonicalLibraryAvailabilityReaders.persistent(context).availableRecordingIds()
    if (recordingId !in available) return CanonicalRediscoveryRecordingState.Unavailable
    val metadata = CanonicalCatalogDatabase.get(context).catalogDao()
        .recordingById(recordingId) ?: return CanonicalRediscoveryRecordingState.Unavailable
    return CanonicalRediscoveryRecordingState.Ready(
        recordingId = recordingId, title = metadata.title, artistDisplay = metadata.artistDisplay,
    )
}

/**
 * Page within the host's existing AnimatedContent navigation, not an AlertDialog.
 * It rechecks canonical MediaAsset/Recording state and fails closed on deletion.
 * No MediaController, queue, play callback, source key, network or Event Store.
 */
@Composable
internal fun CanonicalRediscoveryRecordingDetailsScreen(
    recordingId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val catalog = remember(context) { CanonicalCatalogDatabase.get(context) }
    val changes = remember(context, recordingId) { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    DisposableEffect(catalog, changes) {
        val observer = object : InvalidationTracker.Observer("catalog_recording", "catalog_media_asset") {
            override fun onInvalidated(tables: Set<String>) { changes.tryEmit(Unit) }
        }
        catalog.invalidationTracker.addObserver(observer)
        onDispose { catalog.invalidationTracker.removeObserver(observer) }
    }
    val state by produceState<CanonicalRediscoveryRecordingState>(
        initialValue = CanonicalRediscoveryRecordingState.Loading,
        key1 = recordingId, key2 = changes,
    ) {
        changes.onStart { emit(Unit) }.collectLatest {
            value = try {
                withContext(Dispatchers.IO) { loadCanonicalRecordingDetails(context, recordingId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CanonicalRediscoveryRecordingState.Unavailable
            }
        }
    }
    CanonicalRediscoveryRecordingContent(
        state = state, onBack = onBack,
        feedback = { id -> CanonicalRecordingFeedbackEntry(id) },
    )
}

@Composable
internal fun CanonicalRediscoveryRecordingContent(
    state: CanonicalRediscoveryRecordingState,
    onBack: () -> Unit,
    feedback: (@Composable (String) -> Unit)? = null,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.rediscovery_close),
                    )
                }
                Text(
                    text = stringResource(Res.string.rediscovery_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            when (state) {
                CanonicalRediscoveryRecordingState.Loading -> Text(
                    stringResource(Res.string.rediscovery_loading),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                CanonicalRediscoveryRecordingState.Unavailable -> Text(
                    stringResource(Res.string.rediscovery_unavailable),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                is CanonicalRediscoveryRecordingState.Ready -> {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    Text(
                        text = state.artistDisplay,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    // Rating is a separate, explicitly tapped control. Rendering
                    // this read-only destination never emits a preference write.
                    feedback?.invoke(state.recordingId)
                }
            }
        }
    }
}
