package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Native playlist picker: uses the same Settings group/rows, artwork,
 * DetailSearchField, text-entry and wizard actions as the existing normal
 * playlist editor. No parallel Apple-style sheet or new color/spacing tokens.
 *
 * Smart playlists cannot receive manual members. They are deliberately not
 * listed; their rule editor remains the only way to change their contents.
 */
@Composable
internal fun CanonicalLibraryPlaylistPicker(
    selection: List<CanonicalLibrarySelection>,
    selectedSource: String?,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val repo = remember(app) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(app).staticPlaylistDao(),
        )
    }
    val existing by produceState<List<SavedCanonicalStaticPlaylist>?>(
        initialValue = null, key1 = repo,
    ) {
        repo.observe().collectLatest { value = it }
    }
    var searching by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var wizardSeed by remember { mutableStateOf<List<String>>(emptyList()) }
    var preparingWizard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (saving || selectedId == null) return
        saving = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val resolved = canonicalLibraryResolveSelections(
                        app, selection, selectedSource,
                    )
                    when {
                        resolved.unavailableEntities > 0 ->
                            CanonicalLibraryPlaylistSaveResult.Unavailable(
                                resolved.unavailableEntities,
                            )
                        resolved.recordingIds.isEmpty() -> null
                        else -> addCanonicalLibraryRecordingsToPlaylist(
                            context = app,
                            playlistId = selectedId,
                            newPlaylistName = null,
                            requested = resolved.recordingIds,
                        )
                    }
                }
                when (result) {
                    null -> error = "Nenhuma faixa disponível nesta seleção."
                    CanonicalLibraryPlaylistSaveResult.Missing ->
                        error = "Esta playlist já não está disponível."
                    is CanonicalLibraryPlaylistSaveResult.Unavailable ->
                        error = if (result.count == 1)
                            "Uma seleção não está disponível nesta fonte. Revise antes de adicionar."
                            else "${result.count} seleções não estão disponíveis nesta fonte. Revise antes de adicionar."
                    is CanonicalLibraryPlaylistSaveResult.Saved -> {
                        if (result.added == 0) {
                            error = if (result.unavailable > 0)
                                "As faixas não estão disponíveis na fonte desta playlist."
                            else "As faixas selecionadas já estão nesta playlist."
                        } else {
                            Toast.makeText(app, if (result.added == 1)
                                "1 faixa adicionada à playlist"
                                else "${result.added} faixas adicionadas à playlist",
                                Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Não foi possível salvar. Tente novamente."
            } finally {
                saving = false
            }
        }
    }

    fun openNativeWizard() {
        if (preparingWizard || saving) return
        preparingWizard = true
        error = null
        scope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    canonicalLibraryResolveSelections(app, selection, selectedSource)
                }
                val initial = canonicalLibraryWizardSeed(resolved)
                if (initial == null) {
                    error = "Algumas faixas não estão disponíveis nesta fonte. Revise a seleção."
                } else {
                    wizardSeed = initial
                    // Unmount this destination picker and present the SAME
                    // full editor used by Biblioteca → Criar playlist, only
                    // bounded to a large modal over the still-mounted Library.
                    creating = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Não foi possível confirmar as faixas selecionadas."
            } finally {
                preparingWizard = false
            }
        }
    }

    if (creating) {
        SmartPlaylistBuilderScreen(
            // The editor is a modal over the original selected Library page.
            // Back from its first step returns there, not through another
            // invented playlist-creation destination or nested picker.
            onBack = onDismiss,
            embeddedInLibrary = true,
            initialLibraryRecordingIds = wizardSeed,
            initialLibrarySource = selectedSource,
            onLibraryNormalSaved = { onDismiss() },
        )
        return
    }

    PlaylistWizardSheetChrome(
        title = "Adicionar à playlist",
        onClose = onDismiss,
        canClose = !saving && !preparingWizard,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                PlaylistWizardSearchEntry(
                        value = searching,
                        onValueChange = { searching = it },
                        placeholder = "Buscar playlist",
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Add,
                        title = "Nova playlist",
                        subtitle = "Usar o editor de playlists normal ou inteligente",
                        reserveIconSpace = false,
                        onClick = ::openNativeWizard,
                        trailing = {
                            if (preparingWizard) CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    RowDivider()
                    when (val playlists = existing) {
                        null -> Box(Modifier.fillMaxWidth().heightIn(min = 70.dp),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        else -> {
                            val filtered = playlists.filter {
                                it.name.contains(searching.trim(), ignoreCase = true)
                            }
                            if (filtered.isEmpty()) {
                                Text(
                                    "Nenhuma playlist normal encontrada.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                ) {
                                    items(filtered, key = { it.id }) { saved ->
                                        PlaylistWizardSelectionRow(
                                            label = saved.name,
                                            subtitle = "${saved.recordingIds.size} faixas",
                                            selected = selectedId == saved.id,
                                            onClick = { selectedId = saved.id; error = null },
                                            leading = {
                                                Box(
                                                    Modifier.size(44.dp)
                                                        .clip(RoundedCornerShape(12.dp)),
                                                ) {
                                                    CanonicalStaticPlaylistCoverArt(
                                                        playlist = saved,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            },
                                        )
                                        RowDivider()
                                    }
                                }
                            }
                        }
                    }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PlaylistWizardPrimaryAction(
                    label = if (saving) "Salvando…" else "Adicionar",
                    enabled = !saving && !preparingWizard && selectedId != null,
                    onClick = ::save,
                )
        }
    }
}
