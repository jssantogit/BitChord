package com.music.bitchord.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.catalog.CanonicalBrowseAssetRow
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.opensubsonic.androidOpenSubsonicArtworkLoader
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.listening.CanonicalStaticPlaylistRepository
import com.music.bitchord.data.listening.SavedCanonicalStaticPlaylist
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.ui.components.BrowseActionsSheet
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.NativeInlineTextAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Pinned art is a cover, not a full Library entity card. Keep its name in
 * accessibility semantics, but no visible title, artist, or three-dot action.
 * Long press still reaches the existing manage/unpin menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedLibraryArtworkTile(
    title: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .semantics { contentDescription = title }
            .testTag("pinned-library-artwork"),
        contentAlignment = Alignment.Center,
    ) {
        artwork()
    }
}

internal fun pinnedLocalPlaylistItemsById(
    items: List<ShelfItem>,
): Map<String, ShelfItem> = buildMap {
    items.forEach { item ->
        val id = item.browseId?.takeIf(String::isNotBlank) ?: return@forEach
        put(id, item)
        // Existing LibraryPlaylistCard.Normal prepends "local:" even when
        // its underlying browseId already carries that namespace.
        put("local:$id", item)
    }
}

/**
 * Reuse the same smart playlist preview + cover composition as the main
 * playlist Library row. A missing explicit cover should not become a generic
 * disc if a current, verified playlist membership provides cover artwork.
 */
@Composable
private fun PinnedSmartPlaylistCover(
    definition: SavedSmartPlaylistV2Definition,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val coverRows by produceState<List<V2DisplayRow>>(
        initialValue = emptyList(), key1 = definition.id, key2 = definition.query,
    ) {
        value = try {
            val ids = withContext(Dispatchers.IO) {
                persistentSmartPlaylistPreview(context)
                    .preview(definition.query, definition.id).take(4).map { it.recordingId }
            }
            canonicalNormalPlaylistSelectedRows(context, ids)
                .filterNot { it.title == "Gravação indisponível" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    CanonicalPlaylistCoverArt(
        rows = coverRows,
        modifier = modifier,
        customCoverUri = definition.coverUri,
    )
}

private data class PinnedLibraryCard(
    val pin: CanonicalLibraryPin,
    val title: String,
    val subtitle: String,
    val artwork: CanonicalBrowseTrack?,
    val artistPhotoUrl: String?,
    val accessible: Boolean,
)

/** All artwork is resolved from currently eligible MediaAssets. A pinned
 * ReleaseId or ArtistId never turns an unverified title match into artwork. */
private suspend fun readPinnedLibraryCards(
    dao: CanonicalCatalogDao,
    pins: List<CanonicalLibraryPin>,
    sourceKeys: List<String>,
): List<PinnedLibraryCard> {
    if (pins.isEmpty()) return emptyList()
    val releaseIds = pins.filter { it.selection.type == CanonicalLibraryEntityType.ALBUM }
        .map { it.selection.entityId }
    val artistIds = pins.filter { it.selection.type == CanonicalLibraryEntityType.ARTIST }
        .map { it.selection.entityId }
        .filter { playlistDisplayArtistNameFromGroupId(it) == null }
    val displayArtists = pins.filter { it.selection.type == CanonicalLibraryEntityType.ARTIST }
        .mapNotNull { playlistDisplayArtistNameFromGroupId(it.selection.entityId) }
    val trackIds = pins.filter { it.selection.type == CanonicalLibraryEntityType.TRACK }
        .map { it.selection.entityId }

    val recordings = if (trackIds.isEmpty()) emptyMap() else
        dao.recordingsByIds(trackIds).associateBy { it.id }
    val releases = if (releaseIds.isEmpty()) emptyMap() else
        dao.releasesByIds(releaseIds).associateBy { it.id }
    val artists = if (artistIds.isEmpty()) emptyMap() else
        dao.artistsByIds(artistIds).associateBy { it.id }

    val coverRows = if (sourceKeys.isEmpty()) emptyList() else buildList {
        if (releaseIds.isNotEmpty()) addAll(dao.playlistAlbumCoverRecordingIds(sourceKeys, releaseIds))
        if (artistIds.isNotEmpty()) addAll(dao.playlistCreditedArtistCoverRecordingIds(sourceKeys, artistIds))
        if (displayArtists.isNotEmpty()) addAll(
            dao.playlistDisplayArtistCoverRecordingIds(sourceKeys, displayArtists),
        )
    }
    val covered = coverRows.associate { it.groupId to it.recordingId }
    val wantedIds = (trackIds + coverRows.map { it.recordingId }).distinct()
    val assetRows = if (sourceKeys.isEmpty() || wantedIds.isEmpty()) emptyList()
        else wantedIds.chunked(300).flatMap {
            dao.browseAssetsForRecordingIds(sourceKeys, it)
        }
    val artByRecording = assetRows.groupBy(CanonicalBrowseAssetRow::recordingId)
        .mapValues { (_, group) ->
            val first = group.first()
            val preferredArtwork = group.filter { it.providerKey == "local-mediastore" }
                .firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
                ?: group.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) }
            CanonicalBrowseTrack(
                recordingId = first.recordingId,
                title = first.title,
                artistDisplay = first.artistDisplay,
                durationMs = first.durationMs,
                artworkUri = preferredArtwork,
                availableAssets = group.distinctBy(CanonicalBrowseAssetRow::mediaAssetId).map {
                    CanonicalBrowseMediaAsset(
                        mediaAssetId = it.mediaAssetId,
                        providerKey = it.providerKey,
                        dateAddedSeconds = it.dateAddedSeconds,
                        dateModifiedSeconds = it.dateModifiedSeconds,
                        artworkUri = it.artworkUri,
                    )
                },
            )
        }

    val counts = if (sourceKeys.isNotEmpty() && releaseIds.isNotEmpty())
        dao.scopedReleaseRecordingCounts(releaseIds, sourceKeys)
            .associate { it.releaseId to it.recordingCount } else emptyMap()
    val activeArtists = if (sourceKeys.isNotEmpty() && artistIds.isNotEmpty())
        dao.scopedPinnedArtistIds(sourceKeys, artistIds).toSet() else emptySet()
    val activeDisplayArtists = if (sourceKeys.isNotEmpty() && displayArtists.isNotEmpty())
        dao.scopedPinnedDisplayArtistNames(sourceKeys, displayArtists).toSet() else emptySet()

    return pins.map { pin ->
        val selection = pin.selection
        when (selection.type) {
            CanonicalLibraryEntityType.TRACK -> {
                val recording = recordings[selection.entityId]
                PinnedLibraryCard(
                    pin, recording?.title ?: pin.title,
                    recording?.artistDisplay ?: pin.subtitle,
                    artByRecording[selection.entityId], null,
                    recording != null && selection.entityId in artByRecording,
                )
            }
            CanonicalLibraryEntityType.ALBUM -> {
                val release = releases[selection.entityId]
                PinnedLibraryCard(
                    pin, release?.title ?: pin.title,
                    release?.artistDisplay ?: pin.subtitle,
                    covered[selection.entityId]?.let(artByRecording::get), null,
                    release != null && (counts[selection.entityId] ?: 0) > 0,
                )
            }
            CanonicalLibraryEntityType.ARTIST -> {
                val name = artists[selection.entityId]?.name
                    ?: playlistDisplayArtistNameFromGroupId(selection.entityId)
                    ?: pin.title
                val artworkId = covered[selection.entityId]
                    ?: playlistDisplayArtistNameFromGroupId(selection.entityId)?.let(covered::get)
                PinnedLibraryCard(
                    pin, name, "Artista",
                    artworkId?.let(artByRecording::get),
                    AppleArtistArtRepository.cached(name)?.heroUrl,
                    canonicalPinnedArtistAccessible(
                        selection.entityId, activeArtists, activeDisplayArtists,
                    ),
                )
            }
        }
    }
}

/**
 * Keep canonical pin dispatch outside the already-large BitChordApp
 * composable (the JVM compiler has a hard 64KB method bytecode ceiling).
 */
@Composable
internal fun CanonicalPinnedLibraryHomeSection(
    refreshRequested: Boolean,
    onPlayRecording: (String) -> Unit,
    onOpenEntity: (
        CanonicalLibraryPin,
        com.music.bitchord.data.listening.CanonicalSmartFilterContext,
    ) -> Unit,
    onOpenPlaylist: (String) -> Unit = {},
    onOpenCatalog: (String) -> Unit = {},
    localPlaylists: List<ShelfItem> = emptyList(),
) {
    CanonicalPinnedLibraryShelf(
        refreshRequested = refreshRequested,
        onOpenPlaylist = onOpenPlaylist,
        onOpenCatalog = onOpenCatalog,
        localPlaylists = localPlaylists,
        onOpen = { pin ->
            when (val target = canonicalLibraryPinOpenTarget(pin)) {
                is CanonicalLibraryPinOpenTarget.PlayRecording ->
                    onPlayRecording(target.recordingId)
                is CanonicalLibraryPinOpenTarget.OpenEntity ->
                    onOpenEntity(target.pin, target.category)
            }
        },
    )
}

/** The native Library landing shelf mirrors the reference's 3-column covers
 * BEFORE category links. Nothing is shown when the user has no pins. Six covers
 * are initially visible; the rest expand in-place without a parallel route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CanonicalPinnedLibraryShelf(
    onOpen: (CanonicalLibraryPin) -> Unit,
    refreshRequested: Boolean = false,
    onOpenPlaylist: (String) -> Unit = {},
    onOpenCatalog: (String) -> Unit = {},
    localPlaylists: List<ShelfItem> = emptyList(),
) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) { CanonicalLibraryPinRepository.get(context) }
    val pins by repo.pins.collectAsStateWithLifecycle()
    val playlistPinRepo = remember(context) { LibraryPlaylistPinRepository.get(context) }
    val playlistPins by playlistPinRepo.pins.collectAsStateWithLifecycle()
    val catalogPinRepo = remember(context) { CatalogMetadataPinRepository.get(context) }
    val catalogPins by catalogPinRepo.pins.collectAsStateWithLifecycle()
    if (pins.isEmpty() && playlistPins.isEmpty() && catalogPins.isEmpty()) return

    val smartRepo = remember(context) {
        SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
    }
    val staticRepo = remember(context) {
        CanonicalStaticPlaylistRepository(
            SmartPlaylistDefinitionDatabase.get(context).staticPlaylistDao(),
        )
    }
    val smart by produceState(
        initialValue = emptyList<SavedSmartPlaylistV2Definition>(), key1 = smartRepo,
    ) {
        smartRepo.observe().collectLatest { value = it }
    }
    val normal by produceState(
        initialValue = emptyList<SavedCanonicalStaticPlaylist>(), key1 = staticRepo,
    ) {
        staticRepo.observe().collectLatest { value = it }
    }
    val smartById = remember(smart) { smart.associateBy { "v2:" + it.id } }
    val staticById = remember(normal) { normal.associateBy { "normal:" + it.id } }
    val localById = remember(localPlaylists) {
        pinnedLocalPlaylistItemsById(localPlaylists)
    }

    val db = remember(context) { CanonicalCatalogDatabase.get(context) }
    val dao = remember(db) { db.catalogDao() }
    val loader = remember(context) { androidOpenSubsonicArtworkLoader(context) }
    val cards by produceState(
        initialValue = emptyList<PinnedLibraryCard>(),
        key1 = pins,
        key2 = refreshRequested,
        key3 = db,
    ) {
        db.invalidationTracker.createFlow(
            "catalog_recording", "catalog_media_asset", "catalog_release",
            "catalog_artist", "catalog_release_track",
            "catalog_recording_artist_credit", "catalog_release_artist_credit",
        ).collectLatest {
            value = try {
                withContext(Dispatchers.IO) {
                    val keys = canonicalAvailablePlaylistSourceKeys(context)
                    val cards = readPinnedLibraryCards(dao, pins, keys)
                    runCatching {
                        Diagnostics.event(
                            category = DiagnosticCategory.UI,
                            name = "canonical_library_pins_resolved_v1",
                            metadata = mapOf(
                                "pins" to pins.size.toString(),
                                "available" to cards.count { it.accessible }.toString(),
                                "sources" to keys.size.toString(),
                            ),
                        )
                    }
                    cards
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching {
                    Diagnostics.event(
                        category = DiagnosticCategory.UI,
                        name = "canonical_library_pins_resolution_failed_v1",
                        metadata = mapOf(
                            "pins" to pins.size.toString(),
                            "errorType" to error.javaClass.simpleName,
                        ),
                    )
                }
                // Keep the user's pin identities. A transient catalog read
                // failure must never delete or silently rewrite pinned prefs.
                pins.map { PinnedLibraryCard(it, it.title, it.subtitle, null, null, false) }
            }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<CanonicalLibraryPin?>(null) }
    var playlistOptionsFor by remember { mutableStateOf<LibraryPlaylistPin?>(null) }
    var catalogOptionsFor by remember { mutableStateOf<CatalogMetadataPin?>(null) }
    var ratingCatalogFor by remember { mutableStateOf<CatalogMetadataPin?>(null) }
    var playlistSelection by remember {
        mutableStateOf<List<CanonicalLibrarySelection>?>(null)
    }
    // All three typed identity families share one native 3-column Library shelf.
    // Metadata-only online items never masquerade as available MediaAssets.
    val items: List<Triple<PinnedLibraryCard?, LibraryPlaylistPin?, CatalogMetadataPin?>> =
        cards.map { Triple(it, null, null) } +
        playlistPins.map { Triple(null, it, null) } +
        catalogPins.map { Triple(null, null, it) }
    val visible = if (expanded) items else items.take(6)
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 20.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                NativeLoadingSpinner()
            }
        }
        visible.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (card, playlist, catalog) ->
                    Box(Modifier.weight(1f)) {
                        if (card != null) {
                            PinnedLibraryArtworkTile(
                                title = card.title,
                                onClick = {
                                    if (card.accessible) onOpen(card.pin)
                                    else Toast.makeText(
                                        context, "Item indisponível nas fontes atuais",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onLongPress = { optionsFor = card.pin },
                            ) {
                                when {
                                    card.artistPhotoUrl != null -> AsyncImage(
                                        model = card.artistPhotoUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    card.artwork != null -> CanonicalUnifiedArtwork(
                                        card.artwork, loader, Modifier.fillMaxSize(),
                                    )
                                    else -> com.music.bitchord.ui.components.CoverPlaceholder(
                                        if (card.pin.selection.type == CanonicalLibraryEntityType.ARTIST)
                                            Icons.Rounded.Person else Icons.Rounded.Album,
                                        Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        } else if (playlist != null) {
                            val static = staticById[playlist.id]
                            val smartDefinition = smartById[playlist.id]
                            val legacy = localById[playlist.id]
                            val publicMbid = playlist.id.takeIf { it.startsWith("public:") }
                                ?.removePrefix("public:")
                            val publicArtwork = publicMbid?.let {
                                "https://api.listenbrainz.org/1/art/playlist/$it/opengraph/"
                            }
                            val accessible = static != null || smartDefinition != null ||
                                legacy != null || publicMbid != null
                            PinnedLibraryArtworkTile(
                                title = static?.name ?: smartDefinition?.name
                                    ?: legacy?.title ?: playlist.title,
                                onClick = {
                                    if (accessible) onOpenPlaylist(playlist.id)
                                    else Toast.makeText(
                                        context, "Playlist indisponível", Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onLongPress = { playlistOptionsFor = playlist },
                            ) {
                                when {
                                    static != null -> CanonicalStaticPlaylistCoverArt(
                                        static, modifier = Modifier.fillMaxSize(),
                                    )
                                    smartDefinition != null -> PinnedSmartPlaylistCover(
                                        smartDefinition, modifier = Modifier.fillMaxSize(),
                                    )
                                    legacy?.thumbnailUrl != null || publicArtwork != null ->
                                        AsyncImage(
                                            model = legacy?.thumbnailUrl ?: publicArtwork,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    else -> com.music.bitchord.ui.components.CoverPlaceholder(
                                        Icons.Rounded.Album, Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        } else if (catalog != null) {
                            PinnedLibraryArtworkTile(
                                title = catalog.title,
                                onClick = { onOpenCatalog(catalog.browseId) },
                                onLongPress = { catalogOptionsFor = catalog },
                            ) {
                                if (catalog.artworkUrl != null) AsyncImage(
                                    model = catalog.artworkUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                ) else com.music.bitchord.ui.components.CoverPlaceholder(
                                    if (catalog.browseId.startsWith("catalog:ARTIST:"))
                                        Icons.Rounded.Person else Icons.Rounded.Album,
                                    Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (items.size > 6) {
            NativeInlineTextAction(
                label = if (expanded) "Mostrar menos" else "Ver todos os fixados",
                onClick = { expanded = !expanded },
            )
        }
    }
    catalogOptionsFor?.let { pin ->
        ModalBottomSheet(
            onDismissRequest = { catalogOptionsFor = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(PAGE_GUTTER)) {
                Text(pin.title, style = MaterialTheme.typography.titleLarge)
                NativeInlineTextAction(
                    label = "Avaliar",
                    onClick = {
                        catalogOptionsFor = null
                        ratingCatalogFor = pin
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                NativeInlineTextAction(
                    label = "Desafixar da Biblioteca",
                    onClick = {
                        catalogPinRepo.toggle(pin)
                        catalogOptionsFor = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    subdued = true,
                )
            }
        }
    }
    ratingCatalogFor?.let { pin ->
        canonicalCatalogEntityRatingTarget(pin.browseId)?.let { target ->
            CanonicalCatalogEntityRatingDialog(
                target = target,
                title = if (target.entityType == "ARTIST") "Avaliar artista" else "Avaliar álbum",
                onDismiss = { ratingCatalogFor = null },
            )
        }
    }
    optionsFor?.let { pin ->
        ModalBottomSheet(
            onDismissRequest = { optionsFor = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            BrowseActionsSheet(
                target = BrowseTarget(
                    browseId = null,
                    title = pin.title,
                    subtitle = pin.subtitle,
                    thumbnailUrl = null,
                    type = when (pin.selection.type) {
                        CanonicalLibraryEntityType.TRACK -> BrowseType.OTHER
                        CanonicalLibraryEntityType.ALBUM -> BrowseType.ALBUM
                        CanonicalLibraryEntityType.ARTIST -> BrowseType.ARTIST
                    },
                    fromCard = true,
                ),
                isPinned = true,
                onTogglePin = {
                    repo.toggle(pin)
                    optionsFor = null
                },
                onAddToPlaylist = {
                    playlistSelection = listOf(pin.selection)
                    optionsFor = null
                },
            )
        }
    }
    playlistOptionsFor?.let { pin ->
        ModalBottomSheet(
            onDismissRequest = { playlistOptionsFor = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            BrowseActionsSheet(
                target = BrowseTarget(
                    browseId = null,
                    title = staticById[pin.id]?.name ?: smartById[pin.id]?.name
                        ?: localById[pin.id]?.title ?: pin.title,
                    subtitle = "Playlist",
                    type = BrowseType.PLAYLIST,
                ),
                isPinned = true,
                onTogglePin = {
                    playlistPinRepo.remove(pin.id)
                    playlistOptionsFor = null
                },
                onOpen = {
                    onOpenPlaylist(pin.id)
                    playlistOptionsFor = null
                },
            )
        }
    }
    playlistSelection?.let { selection ->
        CanonicalLibraryPlaylistPicker(
            selection = selection,
            selectedSource = null,
            onDismiss = { playlistSelection = null },
        )
    }
}
