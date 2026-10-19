package com.music.bitchord.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.InvalidationTracker
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.rediscovery_title
import com.music.bitchord.sharedui.resources.rediscovery_loading
import com.music.bitchord.sharedui.resources.rediscovery_days_suffix
import com.music.bitchord.sharedui.resources.rediscovery_reason_rating
import com.music.bitchord.sharedui.resources.rediscovery_reason_behavior
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReaders
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.listening.ArtistAwareDiversityReranker
import com.music.bitchord.data.listening.ExplorationRankedCandidate
import com.music.bitchord.data.listening.LibraryRecommendationCandidateSource
import com.music.bitchord.data.listening.LibraryRecommendationPipeline
import com.music.bitchord.data.listening.LibraryRediscoveryPresentationContract
import com.music.bitchord.data.listening.LibraryRediscoveryPreviewPlanner
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.PersonalRanker
import com.music.bitchord.data.listening.RediscoveryPresentationItem
import com.music.bitchord.data.listening.RediscoveryReason
import com.music.bitchord.data.listening.RoomListeningEventStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.data.listening.SessionContextReranker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RediscoveryShelfTexts(
    val title: String = "Rediscover",
    val loading: String = "Finding songs…",
    val daysSuffix: String = "days since last listen",
    val explicitReason: String = "You liked this",
    val behaviorReason: String = "Played repeatedly",
)

/** Presentation snapshot only. Never contains a sourceKey, provider ID, URL or queue request. */
internal data class RediscoveryShelfRow(
    val item: RediscoveryPresentationItem,
    val title: String,
    val artistDisplay: String,
)

internal sealed interface RediscoveryShelfUiState {
    data object Loading : RediscoveryShelfUiState
    data object Empty : RediscoveryShelfUiState
    data class Ready(val items: List<RediscoveryShelfRow>) : RediscoveryShelfUiState
}

/**
 * L2 adapter: consumes the complete real Library pipeline through #43 and L1.
 * Metadata is read for at most five pre-selected IDs; no new eligibility rule,
 * ranking, score or first-party event is introduced. Availability is checked again
 * after the presentation snapshot to fail closed when a MediaAsset disappeared.
 */
internal class LibraryRediscoveryShelfReader(
    private val fullRanking: suspend () -> List<ExplorationRankedCandidate>,
    private val planner: LibraryRediscoveryPreviewPlanner,
    private val presentation: LibraryRediscoveryPresentationContract,
    private val availability: CanonicalLibraryAvailabilityReader,
    private val recording: suspend (String) -> RecordingEntity?,
) {
    suspend fun load(): RediscoveryShelfUiState {
        val items = presentation.present(planner.preview(fullRanking()))
        if (items.isEmpty()) return RediscoveryShelfUiState.Empty
        val availableIds = availability.availableRecordingIds().toSet()
        val rows = items.mapNotNull { item ->
            if (item.recordingId !in availableIds) return@mapNotNull null
            val metadata = recording(item.recordingId) ?: return@mapNotNull null
            RediscoveryShelfRow(
                item = item,
                title = metadata.title,
                artistDisplay = metadata.artistDisplay,
            )
        }
        return if (rows.isEmpty()) RediscoveryShelfUiState.Empty
        else RediscoveryShelfUiState.Ready(rows)
    }

    /** A shelf click checks current canonical availability and eligibility again. */
    suspend fun resolveForNavigation(recordingId: String): RediscoveryShelfRow? =
        (load() as? RediscoveryShelfUiState.Ready)?.items
            ?.firstOrNull { it.item.recordingId == recordingId }
}

private fun persistentRediscoveryShelfReader(context: Context): LibraryRediscoveryShelfReader {
    val app = context.applicationContext
    val catalog = CanonicalCatalogDatabase.get(app)
    val listening = ListeningEventDatabase.get(app)
    val availability = CanonicalLibraryAvailabilityReaders.persistent(app)
    val behavior = RoomListeningEventStore(listening.listeningEventDao())
    val ratings = RoomRecordingRatingStore(listening.recordingRatingDao())
    val pipeline = LibraryRecommendationPipeline(
        LibraryRecommendationCandidateSource(availability),
        PersonalRanker(behavior, ratings),
        SessionContextReranker(behavior),
        ArtistAwareDiversityReranker(CanonicalRecordingArtistGroupingReaders.persistent(app)),
    )
    return LibraryRediscoveryShelfReader(
        fullRanking = { pipeline.rank() }, // NO limit: #43 eligibility sees the complete ranking
        planner = LibraryRediscoveryPreviewPlanner(),
        presentation = LibraryRediscoveryPresentationContract(),
        availability = availability,
        recording = { id -> catalog.catalogDao().recordingById(id) },
    )
}

/**
 * Android-only Library slot. Room invalidations recompute data outside composition.
 * On click, RecordingId is revalidated then passed to the canonical details route.
 * Legacy track browse clicks autoplay and must never be reused.
 */
@Composable
internal fun LibraryRediscoveryShelfEntry(
    refreshToken: Any? = null,
    onOpenRecording: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val reader = remember(context) { persistentRediscoveryShelfReader(context) }
    val changes = remember(reader) { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val catalog = remember(context) { CanonicalCatalogDatabase.get(context) }
    val listening = remember(context) { ListeningEventDatabase.get(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(catalog, listening, changes) {
        val catalogObserver = object : InvalidationTracker.Observer(
            "catalog_recording", "catalog_media_asset",
        ) {
            override fun onInvalidated(tables: Set<String>) { changes.tryEmit(Unit) }
        }
        val listeningObserver = object : InvalidationTracker.Observer(
            "listening_event", "recording_rating_current",
        ) {
            override fun onInvalidated(tables: Set<String>) { changes.tryEmit(Unit) }
        }
        catalog.invalidationTracker.addObserver(catalogObserver)
        listening.invalidationTracker.addObserver(listeningObserver)
        onDispose {
            catalog.invalidationTracker.removeObserver(catalogObserver)
            listening.invalidationTracker.removeObserver(listeningObserver)
        }
    }
    LaunchedEffect(refreshToken) { changes.emit(Unit) }
    val state by produceState<RediscoveryShelfUiState>(
        initialValue = RediscoveryShelfUiState.Loading, key1 = reader, key2 = changes,
    ) {
        changes.onStart { emit(Unit) }.collectLatest {
            value = try {
                withContext(Dispatchers.IO) { reader.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RediscoveryShelfUiState.Empty // No false "forgotten" claim on failed reads
            }
        }
    }

    val strings = RediscoveryShelfTexts(
        title = stringResource(Res.string.rediscovery_title),
        loading = stringResource(Res.string.rediscovery_loading),
        daysSuffix = stringResource(Res.string.rediscovery_days_suffix),
        explicitReason = stringResource(Res.string.rediscovery_reason_rating),
        behaviorReason = stringResource(Res.string.rediscovery_reason_behavior),
    )
    LibraryRediscoveryShelf(state = state, texts = strings, onRecordingClick = { recordingId ->
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                try { reader.resolveForNavigation(recordingId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            if (resolved != null) onOpenRecording(resolved.item.recordingId)
            else changes.emit(Unit)
        }
    })
}

/** Stable, compact Compose shelf; no independent sorting or selection state. */
@Composable
internal fun LibraryRediscoveryShelf(
    state: RediscoveryShelfUiState,
    onRecordingClick: (String) -> Unit,
    texts: RediscoveryShelfTexts = RediscoveryShelfTexts(),
) {
    if (state == RediscoveryShelfUiState.Empty) return
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = texts.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        when (state) {
            RediscoveryShelfUiState.Loading -> Text(
                text = texts.loading,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            RediscoveryShelfUiState.Empty -> Unit
            is RediscoveryShelfUiState.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items.take(5), key = { it.item.recordingId }) { row ->
                    Card(
                        modifier = Modifier.width(206.dp).clickable {
                            onRecordingClick(row.item.recordingId)
                        },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = row.artistDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${row.item.daysSinceLastAudible} ${texts.daysSuffix}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = if (RediscoveryReason.POSITIVE_EXPLICIT_PREFERENCE in row.item.reasonCodes)
                                    texts.explicitReason else texts.behaviorReason,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
