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
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.catalog.CanonicalRecordingArtistGroupingReaders
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.listening.AdaptiveCollectionKind
import com.music.bitchord.data.listening.AdaptiveCollectionReason
import com.music.bitchord.data.listening.AdaptiveCollectionPreview
import com.music.bitchord.data.listening.AdaptiveSmartCollectionPreview
import com.music.bitchord.data.listening.ArtistAwareDiversityReranker
import com.music.bitchord.data.listening.LibraryRecommendationCandidateSource
import com.music.bitchord.data.listening.LibraryRecommendationPipeline
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.PersonalRanker
import com.music.bitchord.data.listening.PreferenceCollectionIntegritySnapshot
import com.music.bitchord.data.listening.PreferenceSmartCollectionIntegrityEvaluator
import com.music.bitchord.data.listening.RecordingRating
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

internal data class AdaptiveCollectionRow(
    val recordingId: String,
    val title: String,
    val artistDisplay: String,
    val reason: AdaptiveCollectionReason,
)
internal data class AdaptiveCollectionRows(
    val kind: AdaptiveCollectionKind,
    val rows: List<AdaptiveCollectionRow>,
)
internal sealed interface AdaptiveCollectionsUiState {
    data object Loading : AdaptiveCollectionsUiState
    data object Error : AdaptiveCollectionsUiState
    data class Ready(val collections: List<AdaptiveCollectionRows>) : AdaptiveCollectionsUiState
}

/**
 * L11 is a UI projection of L7. It cannot create ranking evidence or infer
 * canonical IDs. The L8 evaluator rejects stale, negative and corrupt snapshots
 * before Catalog metadata is ever presented as a collection.
 */
internal class AdaptiveCollectionsLibraryReader(
    private val preview: suspend () -> List<AdaptiveCollectionPreview>,
    private val availability: CanonicalLibraryAvailabilityReader,
    private val recording: suspend (String) -> RecordingEntity?,
    private val currentRating: suspend (String) -> RecordingRating?,
    private val integrity: PreferenceSmartCollectionIntegrityEvaluator =
        PreferenceSmartCollectionIntegrityEvaluator(),
) {
    suspend fun load(): AdaptiveCollectionsUiState {
        val snapshots = preview()
        if (snapshots.map { it.kind } != AdaptiveCollectionKind.entries.toList()) {
            return AdaptiveCollectionsUiState.Error
        }
        if (snapshots.any { collection -> collection.items.any { item ->
                item.recordingId.isBlank() || item.rankPosition <= 0 ||
                    item.reasons != setOf(collection.expectedReason())
            } }) return AdaptiveCollectionsUiState.Error
        val available = availability.availableRecordingIds().toSet()
        val ids = snapshots.flatMap { it.items }.map { it.recordingId }.distinct()
        val ratings = ids.mapNotNull { currentRating(it) }
        val audit = integrity.evaluate(PreferenceCollectionIntegritySnapshot(
            availableRecordingIds = available,
            currentRatings = ratings,
            inferredPreferences = emptyList(),
            ruleItems = emptyList(),
            adaptiveCollections = snapshots,
        ))
        if (!audit.isValid) return AdaptiveCollectionsUiState.Error
        val sections = snapshots.map { collection ->
            val rows = collection.items.mapNotNull { item ->
                // Metadata is a presentation detail, never identity evidence.
                val metadata = recording(item.recordingId) ?: return@mapNotNull null
                AdaptiveCollectionRow(item.recordingId, metadata.title,
                    metadata.artistDisplay, collection.expectedReason())
            }
            AdaptiveCollectionRows(collection.kind, rows)
        }
        return AdaptiveCollectionsUiState.Ready(sections)
    }

    /** Re-run the real snapshot at tap time; failed reads never open stale IDs. */
    suspend fun resolveForNavigation(kind: AdaptiveCollectionKind, id: String): AdaptiveCollectionRow? =
        if (id.isBlank()) null else (load() as? AdaptiveCollectionsUiState.Ready)
            ?.collections?.firstOrNull { it.kind == kind }
            ?.rows?.firstOrNull { it.recordingId == id }

    private fun AdaptiveCollectionPreview.expectedReason(): AdaptiveCollectionReason = when (kind) {
        AdaptiveCollectionKind.REDISCOVERY -> AdaptiveCollectionReason.CONFIRMED_AUDIBLE_ABSENCE
        AdaptiveCollectionKind.EXPLICIT_POSITIVES -> AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING
        AdaptiveCollectionKind.CONTROLLED_EXPLORATION -> AdaptiveCollectionReason.EXPLORATION_PROMOTION
    }
}

private fun persistentAdaptiveCollectionsReader(context: Context): AdaptiveCollectionsLibraryReader {
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
    val planner = AdaptiveSmartCollectionPreview(pipeline, availability)
    return AdaptiveCollectionsLibraryReader(
        preview = { planner.preview() },
        availability = availability,
        recording = { id -> catalog.catalogDao().recordingById(id) },
        currentRating = { id -> ratings.currentRating(id) },
    )
}

internal data class AdaptiveCollectionsTexts(
    val title: String = "Smart collections",
    val loading: String = "Updating collections…",
    val error: String = "Collections unavailable. Try again.",
    val empty: String = "No eligible recordings yet",
    val rediscovery: String = "Rediscovery",
    val positives: String = "Explicit favorites",
    val exploration: String = "Explore more",
    val rediscoveryReason: String = "Confirmed time away from listening",
    val positivesReason: String = "Your explicit positive rating",
    val explorationReason: String = "Controlled exploration suggestion",
)

@Composable
internal fun AdaptiveCollectionsLibraryEntry(
    refreshToken: Any? = null,
    onOpenRecording: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val reader = remember(context) { persistentAdaptiveCollectionsReader(context) }
    val changes = remember(reader) { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val catalog = remember(context) { CanonicalCatalogDatabase.get(context) }
    val listening = remember(context) { ListeningEventDatabase.get(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(catalog, listening, changes) {
        val catalogObserver = object : InvalidationTracker.Observer(
            "catalog_recording", "catalog_media_asset",
        ) { override fun onInvalidated(tables: Set<String>) { changes.tryEmit(Unit) } }
        val listeningObserver = object : InvalidationTracker.Observer(
            "listening_event", "recording_rating_current",
        ) { override fun onInvalidated(tables: Set<String>) { changes.tryEmit(Unit) } }
        catalog.invalidationTracker.addObserver(catalogObserver)
        listening.invalidationTracker.addObserver(listeningObserver)
        onDispose {
            catalog.invalidationTracker.removeObserver(catalogObserver)
            listening.invalidationTracker.removeObserver(listeningObserver)
        }
    }
    LaunchedEffect(refreshToken) { changes.emit(Unit) }
    val state by produceState<AdaptiveCollectionsUiState>(
        initialValue = AdaptiveCollectionsUiState.Loading, key1 = reader, key2 = changes,
    ) {
        changes.onStart { emit(Unit) }.collectLatest {
            value = try {
                withContext(Dispatchers.IO) { reader.load() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AdaptiveCollectionsUiState.Error
            }
        }
    }
    AdaptiveCollectionsBody(state, onRecordingClick = { kind, id ->
        scope.launch {
            val available = withContext(Dispatchers.IO) {
                try { reader.resolveForNavigation(kind, id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            if (available != null) onOpenRecording(available.recordingId)
            else changes.emit(Unit)
        }
    }, texts = AdaptiveCollectionsTexts())
}

/** UI is read-only: no auto play, provider/native identity, or implicit feedback. */
@Composable
internal fun AdaptiveCollectionsContent(
    state: AdaptiveCollectionsUiState,
    onRecordingClick: (String) -> Unit,
    texts: AdaptiveCollectionsTexts = AdaptiveCollectionsTexts(),
) = AdaptiveCollectionsBody(state, { _, id -> onRecordingClick(id) }, texts)

@Composable
private fun AdaptiveCollectionsBody(
    state: AdaptiveCollectionsUiState,
    onRecordingClick: (AdaptiveCollectionKind, String) -> Unit,
    texts: AdaptiveCollectionsTexts,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(texts.title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        when (state) {
            AdaptiveCollectionsUiState.Loading ->
                Text(texts.loading, Modifier.padding(horizontal = 20.dp))
            AdaptiveCollectionsUiState.Error ->
                Text(texts.error, Modifier.padding(horizontal = 20.dp))
            is AdaptiveCollectionsUiState.Ready -> state.collections.forEach { section ->
                Text(when (section.kind) {
                    AdaptiveCollectionKind.REDISCOVERY -> texts.rediscovery
                    AdaptiveCollectionKind.EXPLICIT_POSITIVES -> texts.positives
                    AdaptiveCollectionKind.CONTROLLED_EXPLORATION -> texts.exploration
                }, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                if (section.rows.isEmpty()) {
                    Text(texts.empty, modifier = Modifier.padding(horizontal = 20.dp))
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(section.rows, key = { it.recordingId }) { row ->
                            Card(Modifier.width(210.dp).clickable {
                                onRecordingClick(section.kind, row.recordingId)
                            }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(row.title, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(row.artistDisplay, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(when (row.reason) {
                                        AdaptiveCollectionReason.CONFIRMED_AUDIBLE_ABSENCE -> texts.rediscoveryReason
                                        AdaptiveCollectionReason.POSITIVE_EXPLICIT_RATING -> texts.positivesReason
                                        AdaptiveCollectionReason.EXPLORATION_PROMOTION -> texts.explorationReason
                                    }, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
