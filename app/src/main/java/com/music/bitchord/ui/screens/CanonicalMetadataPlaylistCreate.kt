package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.ui.components.PillTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Metadata-only MusicBrainz members have real Recording MBIDs but no proven
 * MediaAsset. They may be stored in a normal playlist, never advertised as
 * playable. Spotify IDs, titles and synthetic Song IDs do not pass this gate.
 */
internal fun planCanonicalMetadataPlaylistCreation(
    title: String,
    labels: List<CatalogMetadataRecordingLabel>,
    id: String,
): SavedCanonicalStaticPlaylist? {
    val name = title.trim()
    if (name.length !in 1..80 || name.any(Char::isISOControl) ||
        id.isBlank() || id.length > 100 || id != id.trim() ||
        id.any { it.isISOControl() || it.isWhitespace() } ||
        labels.isEmpty() || labels.size > 500 ||
        labels.any { !validCatalogMetadataRecordingLabel(it) }
    ) return null
    return SavedCanonicalStaticPlaylist(
        id = id,
        name = name,
        recordingIds = labels.distinctBy(CatalogMetadataRecordingLabel::recordingId)
            .map(CatalogMetadataRecordingLabel::recordingId),
        // Metadata isn't tied to a playable provider. Do not claim availability.
        sourceProviderKey = null,
    )
}

/**
 * Persist display labels first. If this fails, never create a playlist whose
 * metadata-only members would appear as anonymous or playable recordings.
 * An orphaned verified label after a database failure is harmless and retryable.
 */
internal suspend fun persistCanonicalMetadataPlaylist(
    title: String,
    labels: List<CatalogMetadataRecordingLabel>,
    id: String,
    rememberLabels: (List<CatalogMetadataRecordingLabel>) -> Boolean,
    savePlaylist: suspend (SavedCanonicalStaticPlaylist) -> Unit,
): SavedCanonicalStaticPlaylist? {
    val planned = planCanonicalMetadataPlaylistCreation(title, labels, id)
        ?: return null
    if (!rememberLabels(labels)) return null
    savePlaylist(planned)
    return planned
}

/**
 * Native name entry for verified metadata-only albums/tracks. The regular
 * source-scoped wizard cannot accept these members: it intentionally requires
 * playable assets at save time. Reuse the same sheet, row and primary action
 * language without fabricating source assets or a second playlist store.
 */
@Composable
internal fun CanonicalMetadataNewPlaylistEditor(
    labels: List<CatalogMetadataRecordingLabel>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val repo = remember(app) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(app).staticPlaylistDao(),
        )
    }
    val labelsRepository = remember(app) {
        CatalogMetadataRecordingLabelRepository.get(app)
    }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (saving) return
        val clean = title.trim()
        if (planCanonicalMetadataPlaylistCreation(clean, labels, "preview") == null) {
            error = "Informe um nome válido de até 80 caracteres e gravações identificadas."
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    persistCanonicalMetadataPlaylist(
                        title = clean,
                        labels = labels,
                        id = UUID.randomUUID().toString(),
                        rememberLabels = labelsRepository::rememberLabels,
                        savePlaylist = repo::save,
                    )
                }
                if (saved == null) {
                    error = "Não foi possível guardar as informações destas faixas."
                } else {
                    Toast.makeText(app, "Playlist normal criada", Toast.LENGTH_SHORT).show()
                    onSaved()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Não foi possível salvar a playlist normal."
            } finally {
                saving = false
            }
        }
    }

    PlaylistWizardSheetChrome(
        title = "Nova playlist",
        onClose = onDismiss,
        canClose = !saving,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Playlist normal", style = MaterialTheme.typography.titleMedium)
            PillTextField(
                value = title,
                onValueChange = { title = it; error = null },
                placeholder = "Nome da playlist",
                enabled = !saving,
            )
            Text(
                "${labels.distinctBy(CatalogMetadataRecordingLabel::recordingId).size} " +
                    "gravações identificadas · áudio não verificado",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            PlaylistWizardPrimaryAction(
                label = if (saving) "Salvando…" else "Salvar",
                enabled = !saving,
                onClick = ::save,
            )
        }
    }
}
