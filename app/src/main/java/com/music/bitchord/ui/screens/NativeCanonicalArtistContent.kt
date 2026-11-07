package com.music.bitchord.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArt
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * Native artist content built exclusively from typed Library assets,
 * evidenced releases and caller-supplied capabilities. No repository
 * lifecycle, fake media IDs or provider-specific navigation.
 */
@Composable
internal fun NativeCanonicalArtistContent(
    artistName: String,
    tracks: List<CanonicalBrowseTrack>,
    heroArtworkTrack: CanonicalBrowseTrack?,
    nativeReleases: CanonicalArtistNativeReleases,
    artistRatingState: CanonicalArtistRatingUiState,
    appleArt: AppleArtistArt?,
    palette: ArtworkPalette,
    listState: androidx.compose.foundation.lazy.LazyListState,
    artworkLoader: OpenSubsonicArtworkLoader,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    selectedItems: List<CanonicalLibrarySelection>,
    onToggleSelection: (CanonicalLibrarySelection) -> Unit,
    onOpenRelease: (ReleaseEntity) -> Unit,
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
    onReleaseOptions: (ReleaseEntity) -> Unit,
    onRateArtist: (() -> Unit)?,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
) {
    val shownTracks = tracks.filter {
        query.isBlank() ||
            it.title.contains(query, ignoreCase = true) ||
            it.artistDisplay.contains(query, ignoreCase = true)
    }
    val sections = nativeReleases.sections
    // Local MediaStore and remote Navidrome are Library contexts: there
    // is no verified popularity ranking to justify "Mais tocadas".
    val visibleSections = nativeArtistVisibleSections(
        NativeArtistSectionEvidence(
            context = NativeArtistContext.LIBRARY,
            hasTracks = shownTracks.isNotEmpty(),
            hasRecentAlbum = sections.recentRelease != null,
            hasAlbums = sections.albums.isNotEmpty(),
            hasSinglesAndEps = sections.singlesAndEps.isNotEmpty(),
            hasUnclassifiedReleases = sections.unclassified.isNotEmpty(),
        ),
    )
    val artistPhotoUrl = canonicalArtistHeroPhotoUrl(
        appleArt?.heroUrl, heroArtworkTrack?.artworkUri,
    )
    // The approved artist DetailScreen extends the artwork's wash behind
    // every shelf, so the photograph merges into the page without a seam.
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.wash)) {
        val targetPhotoHeight = if (shouldCompactNativeArtistHero(artistPhotoUrl)) {
            220.dp
        } else {
            (maxWidth / 0.95f).coerceAtMost(maxHeight * 0.6f)
        }
        val photoHeight by animateDpAsState(targetPhotoHeight, label = "native_artist_hero")
        // Keep the approved DetailScreen backdrop and list structure, while
        // supplying verified Library recordings, ratings and contextual menus.
        NativeArtistDetailFrame(
            palette = palette,
            artHeight = photoHeight,
            listState = listState,
            contentPadding = PaddingValues(bottom = 112.dp),
            heroUrl = artistPhotoUrl,
        ) {
            item(key = "artist-header") {
                ArtistHeader(
                    artistName = artistName, palette = palette,
                    artHeight = photoHeight, appleArt = appleArt, signature = null,
                )
            }
            if (shownTracks.isNotEmpty()) {
                item(key = "artist-actions") {
                    // The native artist page uses three circular buttons,
                    // not the wide album/folder action pills.
                    ActionRow(
                        onPlay = { onPlayAll(shownTracks, false) },
                        onShuffle = { onPlayAll(shownTracks, true) },
                        onRateArtist = if (artistRatingState.target != null)
                            onRateArtist else null,
                        artistRated = artistRatingState.rated,
                        playColor = appleArt?.keyColor?.let { Color(it) },
                    )
                }
            }
            if (shouldShowNativeArtistSearch(searching, query)) {
                item(key = "artist-search") {
                    NativeDetailSearchField(
                        query, onQueryChange, onClose = { onQueryChange("") },
                        autoFocus = searching, onFocused = {}, palette = palette,
                    )
                }
            }
            sections.recentRelease?.takeIf {
                NativeArtistSection.RECENT_ALBUM in visibleSections
            }?.let { release ->
                item(key = "artist-recent-release") {
                    val target = CanonicalLibrarySelection(
                        CanonicalLibraryEntityType.ALBUM, release.id,
                    )
                    NativeArtistTopReleaseCard(
                        title = release.title,
                        subtitle = canonicalArtistReleaseDateLabel(
                            nativeReleases.evidence[release.id]?.firstReleaseDate,
                        ) ?: release.artistDisplay.orEmpty(),
                        label = if (release.id == sections.recentAlbum?.id)
                            stringResource(R.string.recent_album) else "Lançamento recente",
                        palette = palette,
                        onClick = {
                            if (selectedItems.isNotEmpty()) {
                                onToggleSelection(target)
                            } else onOpenRelease(release)
                        },
                        artwork = {
                            val track = nativeReleases.artwork[release.id]
                            if (track != null) CanonicalUnifiedArtwork(
                                track, artworkLoader, Modifier.fillMaxSize(),
                            ) else CoverPlaceholder(
                                Icons.Rounded.Album, Modifier.fillMaxSize(),
                            )
                        },
                        trailing = {
                            if (selectedItems.isNotEmpty()) {
                                IconButton(onClick = { onToggleSelection(target) }) {
                                    Icon(
                                        if (target in selectedItems) Icons.Rounded.CheckCircle
                                        else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = "Selecionar álbum",
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (NativeArtistSection.TRACKS in visibleSections) {
                // The same native artist section now serves Library tracks,
                // while its rows keep RecordingId, selection and native rating.
                item(key = "artist-tracks-shelf") {
                    NativeArtistTracksShelf(
                        title = "Faixas",
                        tracks = shownTracks,
                        palette = palette,
                        horizontalPadding = PAGE_GUTTER,
                        itemKey = { it.recordingId },
                    ) { track ->
                        CanonicalTrackRow(
                            track, artworkLoader,
                            onClick = { onTrackPlay(track) },
                            onEditRating = { onTrackEditRating(track) },
                            onOptions = { onTrackOptions(track) },
                            selectionMode = selectedItems.isNotEmpty(),
                            selected = CanonicalLibrarySelection(
                                CanonicalLibraryEntityType.TRACK, track.recordingId,
                            ) in selectedItems,
                            onToggleSelection = {
                                onToggleSelection(CanonicalLibrarySelection(
                                    CanonicalLibraryEntityType.TRACK, track.recordingId,
                                ))
                            },
                            compactForArtist = true,
                            palette = palette,
                        )
                    }
                }
            }

            fun section(label: String, releases: List<ReleaseEntity>) {
                if (releases.isEmpty()) return
                item(key = "artist-section-$label") {
                    NativeArtistReleaseShelf(
                        title = label,
                        releases = releases,
                        palette = palette,
                        horizontalPadding = PAGE_GUTTER,
                        itemSpacing = 12.dp,
                        itemKey = { it.id },
                    ) { release ->
                        NativeArtistReleaseCard(
                            title = release.title,
                            subtitle = release.artistDisplay.orEmpty(),
                            palette = palette,
                            modifier = Modifier.width(152.dp),
                            onClick = {
                                if (selectedItems.isNotEmpty()) onToggleSelection(
                                    CanonicalLibrarySelection(
                                        CanonicalLibraryEntityType.ALBUM, release.id,
                                    )
                                ) else onOpenRelease(release)
                            },
                            // Artist shelf cards navigate directly. Their
                            // contextual controls belong on the release page.
                            selectionMode = selectedItems.isNotEmpty(),
                            selected = CanonicalLibrarySelection(
                                CanonicalLibraryEntityType.ALBUM, release.id,
                            ) in selectedItems,
                            onToggleSelection = {
                                onToggleSelection(CanonicalLibrarySelection(
                                    CanonicalLibraryEntityType.ALBUM, release.id,
                                ))
                            },
                            artwork = {
                                val track = nativeReleases.artwork[release.id]
                                if (track != null) CanonicalUnifiedArtwork(
                                    track, artworkLoader, Modifier.fillMaxSize(),
                                ) else CoverPlaceholder(
                                    Icons.Rounded.Album, Modifier.fillMaxSize(),
                                )
                            },
                        )
                    }
                }
            }
            if (NativeArtistSection.ALBUMS in visibleSections) {
                section("Álbuns", sections.albums)
            }
            if (NativeArtistSection.SINGLES_EPS in visibleSections) {
                section("Singles e EPs", sections.singlesAndEps)
            }
            if (NativeArtistSection.RELEASES in visibleSections) {
                section("Lançamentos", sections.unclassified)
            }
        }
    }
}
