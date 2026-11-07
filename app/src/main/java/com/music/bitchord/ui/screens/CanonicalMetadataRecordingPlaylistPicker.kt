package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Source-specific picker adapter around the SAME native playlist wizard sheet.
 * Only verified MusicBrainz Recording MBIDs enter the user's normal static
 * playlist; a metadata row cannot enter the queue without a current MediaAsset.
 */
@Composable
internal fun CanonicalMetadataRecordingPlaylistPicker(
    recordingId: String,
    title: String,
    artist: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val recording = remember(recordingId, title, artist) {
        CatalogMetadataRecordingLabel(recordingId, title.trim(), artist.trim())
    }
    val repository = remember(context) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
    }
    val playlists by produceState<List<SavedCanonicalStaticPlaylist>?>(
        initialValue = null, key1 = repository,
    ) {
        repository.observe().collectLatest { value = it }
    }
    var saving by remember(recordingId) { mutableStateOf(false) }
    var error by remember(recordingId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    PlaylistWizardSheetChrome(
        title = "Adicionar à playlist",
        onClose = onDismiss,
        canClose = !saving,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = recording.title + " · " + recording.artist,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (playlists == null) NativeLoadingSpinner()
            else if (playlists.orEmpty().isEmpty()) Text(
                "Crie uma playlist normal na Biblioteca para adicionar esta faixa.",
                style = MaterialTheme.typography.bodyMedium,
            )
            playlists.orEmpty().forEach { selected ->
                SettingsRow(
                    icon = Icons.Rounded.PlaylistAdd,
                    title = selected.name,
                    subtitle = "${selected.recordingIds.size} faixas",
                    onClick = {
                        if (!saving) {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    val added = withContext(Dispatchers.IO) {
                                        require(validCatalogMetadataRecordingLabel(recording))
                                        val actual = repository.list().firstOrNull {
                                            it.id == selected.id
                                        } ?: error("Playlist indisponível")
                                        val ids = planMetadataOnlyPlaylistMembers(
                                            actual.recordingIds, recording.recordingId,
                                        ) ?: error("Identidade inválida")
                                        if (ids == actual.recordingIds) false else {
                                            repository.save(actual.copy(recordingIds = ids))
                                            CatalogMetadataRecordingLabelRepository.get(context)
                                                .rememberLabel(recording)
                                            true
                                        }
                                    }
                                    Toast.makeText(context,
                                        if (added) "Faixa adicionada à playlist"
                                        else "A faixa já consta nesta playlist",
                                        Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                  catch (_: Exception) { error = "Não foi possível adicionar a faixa." }
                                finally { saving = false }
                            }
                        }
                    },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
