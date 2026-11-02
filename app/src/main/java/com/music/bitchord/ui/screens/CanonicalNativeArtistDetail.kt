package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CanonicalArtistNativeReleases(
    val sections: CanonicalArtistReleaseSections,
    val artwork: Map<String, CanonicalBrowseTrack>,
) {
    companion object {
        val EMPTY = CanonicalArtistNativeReleases(
            canonicalArtistReleaseSections(emptyList(), emptyMap()), emptyMap(),
        )
    }
}

/**
 * The artist route reuses the approved DetailScreen artist header, photo,
 * section headings, cards, playback pills and canonical track rows.
 *
 * A Release belongs here only through a verified RecordingId->ReleaseId edge;
 * source/account scope is carried through MediaAsset availability. No Song,
 * legacy browseId or artist ID is inferred from a name.
 */
@Composable
internal fun CanonicalNativeArtistDetail(
    artistName: String,
    tracks: List<CanonicalBrowseTrack>,
    sourceKeys: List<String>,
    selectedSourceKey: String?,
    heroArtworkTrack: CanonicalBrowseTrack?,
    artworkLoader: OpenSubsonicArtworkLoader,
    query: String,
    onQueryChange: (String) -> Unit,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { CanonicalCatalogDatabase.get(context).catalogDao() }
    val keys = sourceKeys.filter { selectedSourceKey == null || it == selectedSourceKey }
    val nativeReleases by produceState(
        initialValue = CanonicalArtistNativeReleases.EMPTY,
        key1 = tracks.map { it.recordingId },
        key2 = keys,
    ) {
        value = try {
            withContext(Dispatchers.IO) {
                val recordingIds = tracks.map { it.recordingId }
                val releaseIds = recordingIds.chunked(300).flatMap {
                    dao.releaseIdsForVisibleRecordings(it)
                }.toSet()
                val releases = keys.flatMap { dao.providerScopedReleases(it) }
                    .distinctBy { it.id }
                    .filter { it.id in releaseIds }
                val proofs = releases.map { it.id }.chunked(300).flatMap {
                    dao.canonicalArtistReleaseEvidence(it)
                }.associate { proof ->
                    proof.releaseId to CanonicalArtistReleaseEvidence(
                        proof.releaseId, proof.primaryType, proof.firstReleaseDate,
                    )
                }
                val coverLinks = releases.map { it.id }.chunked(300).flatMap { ids ->
                    dao.playlistAlbumCoverRecordingIds(keys, ids)
                }
                CanonicalArtistNativeReleases(
                    canonicalArtistReleaseSections(releases, proofs),
                    canonicalLibraryEntityArtworkById(coverLinks, tracks),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CanonicalArtistNativeReleases.EMPTY
        }
    }

    val palette = rememberArtworkPalette(imageUrl = null)
    var appleArt by remember(artistName) {
        mutableStateOf(AppleArtistArtRepository.cached(artistName))
    }
    LaunchedEffect(artistName) {
        appleArt = AppleArtistArtRepository.artFor(artistName) ?: appleArt
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var openedRelease by remember(artistName) { mutableStateOf<ReleaseEntity?>(null) }
    val releaseRecordingIds by produceState(
        initialValue = emptySet<String>(),
        key1 = openedRelease?.id,
        key2 = keys,
    ) {
        val id = openedRelease?.id ?: return@produceState
        value = try {
            withContext(Dispatchers.IO) {
                keys.flatMap { dao.providerScopedReleaseRecordingIds(it, id) }.toSet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
    }
    BackHandler(enabled = openedRelease != null) { openedRelease = null }

    val selectedAlbum = openedRelease
    if (selectedAlbum != null) {
        val albumTracks = tracks.filter { it.recordingId in releaseRecordingIds }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sleeve = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
            LazyColumn(contentPadding = PaddingValues(bottom = 112.dp)) {
                item {
                    NativeReleaseHeaderLayout(
                        title = selectedAlbum.title,
                        credit = selectedAlbum.artistDisplay.orEmpty(),
                        meta = nativeUnifiedTrackCountLabel(albumTracks.size,
                            albumTracks.map { it.durationMs }),
                        palette = palette,
                        sleeveSize = sleeve,
                        artwork = {
                            val art = nativeReleases.artwork[selectedAlbum.id]
                            if (art != null) CanonicalUnifiedArtwork(
                                art, artworkLoader, Modifier.fillMaxSize(),
                            ) else CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
                        },
                        actions = {
                            if (albumTracks.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ActionPill(
                                        icon = BitChordIcons.Play,
                                        label = "Tocar",
                                        onClick = { onPlayAll(albumTracks, false) },
                                        filled = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ActionPill(
                                        icon = BitChordIcons.Shuffle,
                                        label = "Aleatório",
                                        onClick = { onPlayAll(albumTracks, true) },
                                        filled = false,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        },
                    )
                }
                items(albumTracks, key = { it.recordingId }) { track ->
                    CanonicalTrackRow(track, artworkLoader, { onTrackPlay(track) },
                        { onTrackEditRating(track) })
                }
            }
        }
        return
    }

    val shownTracks = tracks.filter {
        query.isBlank() ||
            it.title.contains(query, ignoreCase = true) ||
            it.artistDisplay.contains(query, ignoreCase = true)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val photoHeight = (maxWidth / 0.95f).coerceAtMost(maxHeight * 0.6f)
        PageBackground(
            page = null,
            palette = palette,
            canvas = null,
            artHeight = photoHeight,
            listState = listState,
            heroUrl = appleArt?.heroUrl ?: heroArtworkTrack?.artworkUri?.takeIf {
                it.startsWith("content://") || it.startsWith("file://")
            },
            modifier = Modifier.fillMaxSize(),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            item(key = "artist-header") {
                ArtistHeader(
                    artistName = artistName, palette = palette,
                    artHeight = photoHeight, appleArt = appleArt, signature = null,
                )
            }
            item(key = "artist-actions") {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        nativeUnifiedTrackCountLabel(tracks.size, tracks.map { it.durationMs }),
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ActionPill(BitChordIcons.Play, "Tocar",
                            onClick = { onPlayAll(shownTracks, false) }, filled = true,
                            modifier = Modifier.weight(1f))
                        ActionPill(BitChordIcons.Shuffle, "Aleatório",
                            onClick = { onPlayAll(shownTracks, true) }, filled = false,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            item(key = "artist-search") {
                DetailSearchField(query, onQueryChange, onClose = { onQueryChange("") },
                    autoFocus = false, onFocused = {}, palette = palette)
            }
            if (shownTracks.isNotEmpty()) {
                item(key = "artist-tracks-title") {
                    SectionHeading("Faixas", palette)
                }
                items(shownTracks, key = { it.recordingId }) { track ->
                    CanonicalTrackRow(track, artworkLoader,
                        onClick = { onTrackPlay(track) },
                        onEditRating = { onTrackEditRating(track) },
                    )
                }
            }

            val sections = nativeReleases.sections
            sections.recentAlbum?.let { release ->
                item(key = "artist-recent-album") {
                    SectionHeading("Álbum recente", palette)
                    LibraryRow(
                        title = release.title,
                        subtitle = release.artistDisplay.orEmpty(),
                        imageUrl = null,
                        artwork = nativeReleases.artwork[release.id]?.let { track ->
                            { CanonicalUnifiedArtwork(track, artworkLoader,
                                Modifier.fillMaxSize()) }
                        },
                        onClick = { openedRelease = release },
                        horizontalPadding = PAGE_GUTTER,
                    )
                }
            }
            fun section(label: String, releases: List<ReleaseEntity>) {
                if (releases.isEmpty()) return
                item(key = "artist-section-$label") { SectionHeading(label, palette) }
                item(key = "artist-cards-$label") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(releases, key = { it.id }) { release ->
                            NativeLibraryEntityCard(
                                title = release.title,
                                subtitle = release.artistDisplay.orEmpty(),
                                imageUrl = null,
                                isArtist = false,
                                artwork = nativeReleases.artwork[release.id]?.let { track ->
                                    { CanonicalUnifiedArtwork(track, artworkLoader,
                                        Modifier.fillMaxSize()) }
                                },
                                onClick = { openedRelease = release },
                            )
                        }
                    }
                }
            }
            section("Singles e EPs", sections.singlesAndEps)
            section("Álbuns", sections.albums)
            section("Lançamentos", sections.unclassified)
        }
    }
}
