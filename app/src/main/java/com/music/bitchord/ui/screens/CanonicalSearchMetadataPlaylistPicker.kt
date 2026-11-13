package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Typed online identity, not a fake Song/videoId or provider audio asset. */
internal data class CatalogSearchPlaylistTarget(
    val kind: CanonicalSearchKind?,
    val id: String,
    val title: String,
    val artist: String,
)

internal fun planCatalogMetadataCollectionMembers(
    existing: List<String>,
    labels: List<CatalogMetadataRecordingLabel>,
): List<String>? {
    if (labels.isEmpty() || labels.size > 500 ||
        labels.any { !validCatalogMetadataRecordingLabel(it) }) return null
    var result = existing
    for (label in labels.distinctBy(CatalogMetadataRecordingLabel::recordingId)) {
        result = planMetadataOnlyPlaylistMembers(result, label.recordingId) ?: return null
    }
    return result
}

private sealed interface MetadataCollectionLoad {
    data object Loading : MetadataCollectionLoad
    data class Ready(
        val labels: List<CatalogMetadataRecordingLabel>,
        val skipped: Int = 0,
    ) : MetadataCollectionLoad
    data class Error(val message: String) : MetadataCollectionLoad
}

/**
 * One native picker for recording, edition and public-playlist search results.
 * Albums load an exact MusicBrainz edition. ReleaseGroups with multiple
 * editions require an explicit choice, never a guessed "representative".
 * A ListenBrainz list imports only entries carrying genuine Recording MBIDs.
 */
@Composable
internal fun CanonicalSearchMetadataPlaylistPicker(
    target: CatalogSearchPlaylistTarget,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val repository = remember(app) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(app).staticPlaylistDao(),
        )
    }
    val labelRepository = remember(app) { CatalogMetadataRecordingLabelRepository.get(app) }
    val graph = remember { MusicBrainzCatalogGraph() }
    val publicClient = remember { ListenBrainzPublicPlaylistClient() }
    val scope = rememberCoroutineScope()
    val playlists by produceState<List<SavedCanonicalStaticPlaylist>?>(
        initialValue = null, key1 = repository,
    ) { repository.observe().collectLatest { value = it } }
    val editions by produceState<List<MusicBrainzReleaseHit>?>(
        initialValue = null, key1 = target,
    ) {
        if (target.kind != CanonicalSearchKind.RELEASE_GROUP) return@produceState
        value = try {
            withContext(Dispatchers.IO) {
                (graph.browseGroupReleases(target.id, limit = 50) as?
                    MusicBrainzCatalogGraphResult.Success)?.value?.items
                    ?.filter { it.musicBrainzId.isNotBlank() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }
    var chosenEdition by remember(target) { mutableStateOf<String?>(null) }
    val editionId = when (target.kind) {
        CanonicalSearchKind.RELEASE_GROUP -> chosenEdition
            ?: editions?.singleOrNull()?.musicBrainzId
        CanonicalSearchKind.RELEASE -> target.id
        else -> null
    }
    val resolved by produceState<MetadataCollectionLoad>(
        initialValue = MetadataCollectionLoad.Loading,
        key1 = target, key2 = editionId,
    ) {
        value = try {
            when (target.kind) {
                CanonicalSearchKind.RECORDING -> {
                    val row = CatalogMetadataRecordingLabel(
                        target.id, target.title.trim(), target.artist.trim(),
                    )
                    if (validCatalogMetadataRecordingLabel(row))
                        MetadataCollectionLoad.Ready(listOf(row))
                    else MetadataCollectionLoad.Error("Esta gravação não possui identidade e créditos completos.")
                }
                CanonicalSearchKind.RELEASE, CanonicalSearchKind.RELEASE_GROUP -> {
                    if (editionId == null) MetadataCollectionLoad.Loading
                    else withContext(Dispatchers.IO) {
                        when (val result = graph.lookupRelease(editionId)) {
                            is MusicBrainzCatalogGraphResult.Failure ->
                                MetadataCollectionLoad.Error("Não foi possível consultar esta edição.")
                            is MusicBrainzCatalogGraphResult.Success -> {
                                val details = result.value
                                val proof = details.completeness
                                if (!proof.countsExplicitAndExact || !proof.offsetsValid ||
                                    !proof.positionsExplicitUnique || details.tracks.isEmpty() ||
                                    details.tracks.size > 500
                                ) MetadataCollectionLoad.Error(
                                    "A lista desta edição ainda não foi verificada integralmente.",
                                )
                                else {
                                    val labels = details.tracks.map { track ->
                                        CatalogMetadataRecordingLabel(
                                            track.recordingMbid, track.title.trim(),
                                            (track.artistDisplay ?: details.artistDisplay).orEmpty().trim(),
                                        )
                                    }
                                    if (labels.any { !validCatalogMetadataRecordingLabel(it) })
                                        MetadataCollectionLoad.Error(
                                            "Esta edição tem gravações sem identificação suficiente.",
                                        )
                                    else MetadataCollectionLoad.Ready(labels)
                                }
                            }
                        }
                    }
                }
                null -> withContext(Dispatchers.IO) {
                    val selected = ListenBrainzPublicPlaylist(
                        target.id, target.title, target.artist, null,
                    )
                    val response = publicClient.detail(selected)
                        ?: return@withContext MetadataCollectionLoad.Error(
                            "Não foi possível consultar as faixas da playlist pública.",
                        )
                    val verified = response.tracks.mapNotNull { item ->
                        val id = item.recordingMbid ?: return@mapNotNull null
                        CatalogMetadataRecordingLabel(id, item.title.trim(), item.artist.trim())
                            .takeIf(::validCatalogMetadataRecordingLabel)
                    }
                    if (verified.isEmpty() || verified.size > 500)
                        MetadataCollectionLoad.Error(
                            "Nenhuma lista completa de gravações identificadas está disponível.",
                        )
                    else MetadataCollectionLoad.Ready(
                        verified, response.tracks.size - verified.size,
                    )
                }
                CanonicalSearchKind.ARTIST -> MetadataCollectionLoad.Error(
                    "Selecione um álbum ou uma faixa para adicionar à playlist.",
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              MetadataCollectionLoad.Error("Não foi possível consultar esta coleção.")
          }
    }
    var saving by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    PlaylistWizardSheetChrome(
        title = "Adicionar à playlist",
        onClose = onDismiss,
        canClose = !saving,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(target.title, style = MaterialTheme.typography.titleMedium)
            if (target.kind == CanonicalSearchKind.RELEASE_GROUP &&
                chosenEdition == null && editions != null && editions.size > 1) {
                Text("Escolha a edição cujas faixas deseja adicionar.")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(editions, key = { it.musicBrainzId }) { edition ->
                        SettingsRow(
                            title = edition.title,
                            subtitle = listOfNotNull(edition.country, edition.releaseDate)
                                .joinToString(" · ").ifBlank { "Edição MusicBrainz" },
                            onClick = { chosenEdition = edition.musicBrainzId },
                        )
                    }
                }
            } else if (target.kind == CanonicalSearchKind.RELEASE_GROUP &&
                editions != null && editions.isEmpty()) {
                Text("Nenhuma edição verificada encontrada para este álbum.")
            } else when (val state = resolved) {
                MetadataCollectionLoad.Loading -> NativeLoadingSpinner()
                is MetadataCollectionLoad.Error -> Text(state.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                is MetadataCollectionLoad.Ready -> {
                    Text(
                        "${state.labels.size} gravações identificadas · " +
                            "sem garantia de áudio disponível",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.skipped > 0) Text(
                        "${state.skipped} faixas sem Recording MBID não serão importadas.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (playlists == null) NativeLoadingSpinner()
                    else if (playlists.orEmpty().isEmpty()) Text(
                        "Crie uma playlist normal na Biblioteca para adicionar estas faixas.",
                    )
                    playlists.orEmpty().forEach { candidate ->
                        SettingsRow(
                            title = candidate.name,
                            subtitle = "${candidate.recordingIds.size} faixas",
                            onClick = {
                                if (!saving) {
                                    saving = true
                                    error = null
                                    scope.launch {
                                        try {
                                            val added = withContext(Dispatchers.IO) {
                                                val actual = repository.list()
                                                    .firstOrNull { it.id == candidate.id }
                                                    ?: kotlin.error("Playlist removida")
                                                val planned = planCatalogMetadataCollectionMembers(
                                                    actual.recordingIds, state.labels,
                                                ) ?: kotlin.error("Identidade inválida")
                                                val changed = planned.size - actual.recordingIds.size
                                                if (changed > 0) {
                                                    repository.save(actual.copy(recordingIds = planned))
                                                    labelRepository.rememberLabels(state.labels)
                                                }
                                                changed
                                            }
                                            Toast.makeText(app, if (added > 0)
                                                "${added} faixas adicionadas à playlist"
                                                else "Estas faixas já estão na playlist",
                                                Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                          catch (_: Exception) {
                                              error = "Não foi possível adicionar as faixas."
                                          } finally { saving = false }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
