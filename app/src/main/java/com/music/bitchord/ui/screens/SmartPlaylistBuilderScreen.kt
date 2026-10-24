package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.listening.CanonicalRuleBasedSmartPlaylistPreview
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.ListeningBehaviorReader
import com.music.bitchord.data.listening.RoomListeningEventStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore
import com.music.bitchord.data.listening.RuleBasedInclusionReason
import com.music.bitchord.data.listening.RuleBasedSmartPlaylistQuery
import com.music.bitchord.data.listening.SavedSmartPlaylistDefinition
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistDefinitionRepository
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.smart_playlist_title
import com.music.bitchord.sharedui.resources.smart_playlist_name
import com.music.bitchord.sharedui.resources.smart_playlist_rating
import com.music.bitchord.sharedui.resources.smart_playlist_completions
import com.music.bitchord.sharedui.resources.smart_playlist_min_age
import com.music.bitchord.sharedui.resources.smart_playlist_max_age
import com.music.bitchord.sharedui.resources.smart_playlist_min_duration
import com.music.bitchord.sharedui.resources.smart_playlist_limit
import com.music.bitchord.sharedui.resources.smart_playlist_preview
import com.music.bitchord.sharedui.resources.smart_playlist_save
import com.music.bitchord.sharedui.resources.smart_playlist_edit
import com.music.bitchord.sharedui.resources.smart_playlist_delete
import com.music.bitchord.sharedui.resources.smart_playlist_reset
import com.music.bitchord.sharedui.resources.smart_playlist_saved
import com.music.bitchord.sharedui.resources.smart_playlist_saved_loading
import com.music.bitchord.sharedui.resources.smart_playlist_saved_empty
import com.music.bitchord.sharedui.resources.smart_playlist_saved_error
import com.music.bitchord.sharedui.resources.smart_playlist_empty
import com.music.bitchord.sharedui.resources.smart_playlist_error
import com.music.bitchord.sharedui.resources.smart_playlist_overflow
import com.music.bitchord.sharedui.resources.smart_playlist_loading
import com.music.bitchord.sharedui.resources.smart_playlist_close
import com.music.bitchord.sharedui.resources.smart_playlist_reason_rating
import com.music.bitchord.sharedui.resources.smart_playlist_reason_completion
import com.music.bitchord.sharedui.resources.smart_playlist_reason_absence
import com.music.bitchord.sharedui.resources.smart_playlist_reason_recency
import com.music.bitchord.sharedui.resources.smart_playlist_reason_duration
import com.music.bitchord.sharedui.resources.smart_collection_batch_play
import com.music.bitchord.sharedui.resources.smart_collection_batch_queue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.util.UUID

/** The only editable criteria are already supported by L6, combined as AND rules. */
internal data class SmartPlaylistEditorDraft(
    val name: String = "",
    val minimumRating: String = "",
    val minimumCompletions: String = "",
    val minimumDaysSinceAudible: String = "",
    val maximumDaysSinceAudible: String = "",
    val minimumDurationMinutes: String = "",
    val limit: String = "50",
) {
    fun toQuery(): RuleBasedSmartPlaylistQuery {
        fun optionalLong(text: String): Long? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return trimmed.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer rule")
        }
        fun optionalRating(text: String): Double? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return trimmed.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid rating rule")
        }
        val minutes = optionalLong(minimumDurationMinutes)
        require(minutes == null || minutes in 1..(Long.MAX_VALUE / 60_000L)) {
            "Invalid canonical duration rule"
        }
        return RuleBasedSmartPlaylistQuery(
            minimumExplicitRating = optionalRating(minimumRating),
            minimumCompletions = optionalLong(minimumCompletions),
            minimumDaysSinceAudible = optionalLong(minimumDaysSinceAudible),
            maximumDaysSinceAudible = optionalLong(maximumDaysSinceAudible),
            minimumDurationMs = minutes?.times(60_000L),
            limit = limit.trim().toIntOrNull() ?: throw IllegalArgumentException("Invalid limit"),
        )
    }

    companion object {
        fun from(definition: SavedSmartPlaylistDefinition): SmartPlaylistEditorDraft {
            val query = definition.query
            return SmartPlaylistEditorDraft(
                name = definition.name,
                minimumRating = query.minimumExplicitRating?.toString().orEmpty(),
                minimumCompletions = query.minimumCompletions?.toString().orEmpty(),
                minimumDaysSinceAudible = query.minimumDaysSinceAudible?.toString().orEmpty(),
                maximumDaysSinceAudible = query.maximumDaysSinceAudible?.toString().orEmpty(),
                minimumDurationMinutes = query.minimumDurationMs?.let {
                    (it / 60_000L).toString()
                }.orEmpty(),
                limit = query.limit.toString(),
            )
        }
    }
}

internal data class SmartPlaylistPreviewRow(
    val recordingId: String,
    val title: String,
    val artistDisplay: String,
    val reasons: Set<RuleBasedInclusionReason>,
)

@Composable
internal fun SmartPlaylistBuilderEntry(onOpen: () -> Unit) {
    TextButton(onClick = onOpen) {
        Text(stringResource(Res.string.smart_playlist_title))
    }
}

/** Saved definitions are distinct from a failed Room observation. */
internal sealed interface SmartPlaylistSavedUiState {
    data object Loading : SmartPlaylistSavedUiState
    data object Empty : SmartPlaylistSavedUiState
    data object Error : SmartPlaylistSavedUiState
    data class Content(val definitions: List<SavedSmartPlaylistDefinition>) : SmartPlaylistSavedUiState
}

internal suspend fun collectSmartPlaylistSavedState(
    updates: Flow<List<SavedSmartPlaylistDefinition>>,
    publish: (SmartPlaylistSavedUiState) -> Unit,
) {
    try {
        updates.collect { definitions ->
            publish(if (definitions.isEmpty()) SmartPlaylistSavedUiState.Empty else {
                SmartPlaylistSavedUiState.Content(definitions)
            })
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        publish(SmartPlaylistSavedUiState.Error)
    }
}

@Composable
internal fun SmartPlaylistSavedStatus(state: SmartPlaylistSavedUiState) {
    when (state) {
        SmartPlaylistSavedUiState.Loading ->
            Text(stringResource(Res.string.smart_playlist_saved_loading))
        SmartPlaylistSavedUiState.Empty ->
            Text(stringResource(Res.string.smart_playlist_saved_empty))
        SmartPlaylistSavedUiState.Error ->
            Text(
                stringResource(Res.string.smart_playlist_saved_error),
                color = MaterialTheme.colorScheme.error,
            )
        is SmartPlaylistSavedUiState.Content -> Unit
    }
}

@Composable
internal fun SmartPlaylistBuilderHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(Res.string.smart_playlist_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onBack) {
            Text(stringResource(Res.string.smart_playlist_close))
        }
    }
}

@Composable
internal fun SmartPlaylistBuilderScreen(
    onBack: () -> Unit,
    onSavedAction: ((String, CanonicalCollectionIntent) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) {
        SmartPlaylistDefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).definitionDao(),
        )
    }
    val availability = remember(context) {
        CanonicalLibraryAvailabilityReaders.persistent(context)
    }
    val catalog = remember(context) { CanonicalCatalogDatabase.get(context) }
    val readOnlyPreview = remember(context, availability) {
        persistentSmartPlaylistPreview(context, availability)
    }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(SmartPlaylistEditorDraft()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<SmartPlaylistPreviewRow>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val savedState by produceState<SmartPlaylistSavedUiState>(
        initialValue = SmartPlaylistSavedUiState.Loading, key1 = repository,
    ) {
        try {
            collectSmartPlaylistSavedState(repository.observe()) { value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Opening the query can fail before the Flow is returned.
            value = SmartPlaylistSavedUiState.Error
        }
    }
    val saved = (savedState as? SmartPlaylistSavedUiState.Content)?.definitions.orEmpty()

    fun clearForm() {
        editingId = null
        draft = SmartPlaylistEditorDraft()
        preview = null
        error = null
    }
    fun edit(definition: SavedSmartPlaylistDefinition) {
        editingId = definition.id
        draft = SmartPlaylistEditorDraft.from(definition)
        preview = null
        error = null
    }

    val errorText = stringResource(Res.string.smart_playlist_error)
    val overflowText = stringResource(Res.string.smart_playlist_overflow)
    val reasonLabels = mapOf(
        RuleBasedInclusionReason.EXPLICIT_RATING to stringResource(Res.string.smart_playlist_reason_rating),
        RuleBasedInclusionReason.CONFIRMED_COMPLETION to stringResource(Res.string.smart_playlist_reason_completion),
        RuleBasedInclusionReason.AUDIBLE_ABSENCE to stringResource(Res.string.smart_playlist_reason_absence),
        RuleBasedInclusionReason.AUDIBLE_RECENCY to stringResource(Res.string.smart_playlist_reason_recency),
        RuleBasedInclusionReason.TRUSTED_DURATION to stringResource(Res.string.smart_playlist_reason_duration),
    )

    // D13 owns route/first-draw truth. This is only bounded content-state evidence.
    val diagnosticTruth = remember(savedState, preview?.size, busy, error != null) {
        val savedStatus = when (savedState) {
            SmartPlaylistSavedUiState.Loading -> "loading"
            SmartPlaylistSavedUiState.Empty -> "empty"
            SmartPlaylistSavedUiState.Error -> "error"
            is SmartPlaylistSavedUiState.Content -> "content"
        }
        mapOf(
            "savedState" to savedStatus,
            "savedCount" to when (savedState) {
                SmartPlaylistSavedUiState.Empty -> "0"
                is SmartPlaylistSavedUiState.Content -> saved.size.coerceIn(0, 10000).toString()
                else -> "unknown"
            },
            "previewState" to when {
                preview == null -> "unrequested"
                preview!!.isEmpty() -> "empty"
                else -> "content"
            },
            "previewCount" to (preview?.size?.coerceIn(0, 10000)?.toString() ?: "unknown"),
            "editorBusy" to busy.toString(),
            "editorError" to (error != null).toString(),
            "visibleProven" to "unknown",
        )
    }
    LaunchedEffect(diagnosticTruth) {
        try {
            Diagnostics.event(
                category = DiagnosticCategory.UI,
                name = "smart_playlist_builder_content_state_v1",
                metadata = diagnosticTruth,
            )
        } catch (_: Exception) {
            // A logging failure cannot affect saved rules or editing.
        }
    }

    ListeningLibraryPage {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            SmartPlaylistBuilderHeader(onBack)
        }
        item(key = "editor") {
            Column {
                @Composable
                fun field(value: String, onValue: (String) -> Unit, label: String) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { onValue(it); preview = null; error = null },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                field(draft.name, { draft = draft.copy(name = it) },
                    stringResource(Res.string.smart_playlist_name))
                field(draft.minimumRating, { draft = draft.copy(minimumRating = it) },
                    stringResource(Res.string.smart_playlist_rating))
                field(draft.minimumCompletions, { draft = draft.copy(minimumCompletions = it) },
                    stringResource(Res.string.smart_playlist_completions))
                field(draft.minimumDaysSinceAudible, { draft = draft.copy(minimumDaysSinceAudible = it) },
                    stringResource(Res.string.smart_playlist_min_age))
                field(draft.maximumDaysSinceAudible, { draft = draft.copy(maximumDaysSinceAudible = it) },
                    stringResource(Res.string.smart_playlist_max_age))
                field(draft.minimumDurationMinutes, { draft = draft.copy(minimumDurationMinutes = it) },
                    stringResource(Res.string.smart_playlist_min_duration))
                field(draft.limit, { draft = draft.copy(limit = it) },
                    stringResource(Res.string.smart_playlist_limit))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            try {
                                val query = draft.toQuery()
                                val id = editingId ?: UUID.randomUUID().toString()
                                withContext(Dispatchers.IO) {
                                    repository.save(SavedSmartPlaylistDefinition(id, draft.name, query))
                                }
                                clearForm()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = errorText
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy) {
                        Text(stringResource(Res.string.smart_playlist_save))
                    }
                    TextButton(onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                val query = draft.toQuery()
                                val count = withContext(Dispatchers.IO) {
                                    availability.availableRecordingIds().distinct().size
                                }
                                if (count > 5_000) {
                                    error = overflowText
                                    preview = null
                                } else {
                                    val rows = withContext(Dispatchers.IO) {
                                        readOnlyPreview.preview(query).mapNotNull { item ->
                                            val metadata = catalog.catalogDao().recordingById(item.recordingId)
                                                ?: return@mapNotNull null
                                            SmartPlaylistPreviewRow(
                                                recordingId = item.recordingId,
                                                title = metadata.title,
                                                artistDisplay = metadata.artistDisplay,
                                                reasons = item.reasons,
                                            )
                                        }
                                    }
                                    preview = rows
                                    error = null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = errorText
                                preview = null
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy) {
                        Text(stringResource(Res.string.smart_playlist_preview))
                    }
                    TextButton(onClick = ::clearForm, enabled = !busy) {
                        Text(stringResource(Res.string.smart_playlist_reset))
                    }
                }
                if (busy) Text(stringResource(Res.string.smart_playlist_loading))
                if (error != null) Text(error.orEmpty())
            }
        }
        item(key = "preview-heading") {
            if (preview != null) {
                Text(stringResource(Res.string.smart_playlist_preview),
                    style = MaterialTheme.typography.titleMedium)
                if (preview!!.isEmpty()) Text(stringResource(Res.string.smart_playlist_empty))
            }
        }
        items(preview.orEmpty(), key = { "preview-" + it.recordingId }) { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.artistDisplay, style = MaterialTheme.typography.bodySmall)
                Text(row.reasons.joinToString(" · ") { reason -> reasonLabels.getValue(reason) },
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        item(key = "saved-heading") {
            Text(stringResource(Res.string.smart_playlist_saved),
                style = MaterialTheme.typography.titleLarge)
            SmartPlaylistSavedStatus(savedState)
        }
        items(saved, key = { "saved-" + it.id }) { item ->
            Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(item.name, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { edit(item) }, enabled = !busy) {
                    Text(stringResource(Res.string.smart_playlist_edit))
                }
                TextButton(onClick = {
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { repository.remove(item.id) }
                            if (editingId == item.id) clearForm()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = errorText
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy) {
                    Text(stringResource(Res.string.smart_playlist_delete))
                }
            }
            if (onSavedAction != null) {
                Row {
                    TextButton(onClick = {
                        onSavedAction(item.id, CanonicalCollectionIntent.PLAY_NOW)
                    }, enabled = !busy) {
                        Text(stringResource(Res.string.smart_collection_batch_play))
                    }
                    TextButton(onClick = {
                        onSavedAction(item.id, CanonicalCollectionIntent.ADD_TO_QUEUE)
                    }, enabled = !busy) {
                        Text(stringResource(Res.string.smart_collection_batch_queue))
                    }
                }
            }
            }
        }
    }
    }
}
