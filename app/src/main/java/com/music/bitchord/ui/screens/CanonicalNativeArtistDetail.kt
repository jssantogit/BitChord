package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.keyColors
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.theme.rememberArtworkPalette

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
    artistId: String?,
    tracks: List<CanonicalBrowseTrack>,
    availableLibraryTracks: List<CanonicalBrowseTrack>,
    sourceKeys: List<String>,
    selectedSourceKey: String?,
    heroArtworkTrack: CanonicalBrowseTrack?,
    artworkLoader: OpenSubsonicArtworkLoader,
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    nestedBackToken: Int,
    onNestedDetailChanged: (Boolean) -> Unit,
    albumSort: SongSort = SongSort.DEFAULT,
    albumMenuToken: Int = 0,
    albumPlaylistToken: Int = 0,
    onNestedAlbumAddToPlaylist: (ReleaseEntity) -> Unit = {},
    onTrackPlay: (CanonicalBrowseTrack) -> Unit,
    onTrackEditRating: (CanonicalBrowseTrack) -> Unit,
    onTrackOptions: (CanonicalBrowseTrack) -> Unit,
    onReleaseOptions: (ReleaseEntity) -> Unit,
    onRateArtist: (() -> Unit)? = null,
    onRateRelease: ((ReleaseEntity) -> Unit)? = null,
    selectedItems: List<CanonicalLibrarySelection>,
    onToggleSelection: (CanonicalLibrarySelection) -> Unit,
    onPlayAll: (List<CanonicalBrowseTrack>, Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val pinRepository = remember(context) { CanonicalLibraryPinRepository.get(context) }
    val libraryPins by pinRepository.pins.collectAsStateWithLifecycle()
    // Reactive release evidence is now owned by the shared artist detail state;
    // this renderer only handles UI/navigation until its native replacement.
    val nativeReleases by rememberCanonicalArtistReleases(
        tracks, sourceKeys, selectedSourceKey,
    )

    var appleArt by remember(artistName) {
        mutableStateOf(AppleArtistArtRepository.cached(artistName))
    }
    LaunchedEffect(artistName) {
        appleArt = AppleArtistArtRepository.artFor(artistName) ?: appleArt
    }
    val palette = rememberArtworkPalette(
        imageUrl = appleArt?.heroUrl,
        keyColors = appleArt?.keyColors(),
    )

    // The rating observer is shared with the native artist detail contract.
    // It keeps DISPLAY_ARTIST separate from a verified canonical ArtistId.
    val artistRatingState by rememberCanonicalArtistRating(artistId)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var openedRelease by remember(canonicalArtistDetailNavigationKey(artistId, artistName)) {
        mutableStateOf<ReleaseEntity?>(null)
    }
    LaunchedEffect(openedRelease?.id) {
        onNestedDetailChanged(openedRelease != null)
        if (openedRelease != null) onQueryChange("")
    }
    LaunchedEffect(nestedBackToken) {
        if (nestedBackToken > 0) openedRelease = null
    }
    LaunchedEffect(albumMenuToken) {
        if (albumMenuToken > 0) openedRelease?.let(onReleaseOptions)
    }
    LaunchedEffect(albumPlaylistToken) {
        if (albumPlaylistToken > 0) openedRelease?.let(onNestedAlbumAddToPlaylist)
    }
    val releaseRecordingIds by rememberCanonicalArtistNestedRecordings(
        openedRelease?.id, sourceKeys, selectedSourceKey,
    )
    BackHandler(enabled = openedRelease != null) { openedRelease = null }

    val selectedAlbum = openedRelease
    if (selectedAlbum != null) {
        val albumSelection = CanonicalLibrarySelection(
            CanonicalLibraryEntityType.ALBUM, selectedAlbum.id,
        )
        NativeCanonicalNestedAlbumDetail(
            release = selectedAlbum,
            releaseRecordingIds = releaseRecordingIds,
            availableLibraryTracks = availableLibraryTracks,
            selectedSourceKey = selectedSourceKey,
            artworkTrack = nativeReleases.artwork[selectedAlbum.id],
            evidence = nativeReleases.evidence[selectedAlbum.id],
            artworkLoader = artworkLoader,
            albumSort = albumSort,
            query = query,
            searching = searching,
            onQueryChange = onQueryChange,
            isPinned = albumSelection in libraryPins.map { it.selection },
            onTogglePin = {
                pinRepository.toggle(CanonicalLibraryPin(
                    selection = albumSelection,
                    title = selectedAlbum.title,
                    subtitle = selectedAlbum.artistDisplay.orEmpty(),
                ))
            },
            onRateRelease = onRateRelease?.let { edit -> { edit(selectedAlbum) } },
            onPlayAll = onPlayAll,
            onTrackPlay = onTrackPlay,
            onTrackEditRating = onTrackEditRating,
            onTrackOptions = onTrackOptions,
        )
        return
    }

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
            hasRecentAlbum = sections.recentAlbum != null,
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
                    DetailSearchField(
                        query, onQueryChange, onClose = { onQueryChange("") },
                        autoFocus = searching, onFocused = {}, palette = palette,
                    )
                }
            }
            if (NativeArtistSection.TRACKS in visibleSections) {
                item(key = "artist-tracks-title") {
                    SectionHeading("Faixas", palette)
                }
                // Like DetailScreen's native artist page, the songs page
                // sideways in four-row columns; they must not push all the
                // album/single carousels several screens below the fold.
                item(key = "artist-tracks-carousel") {
                    NativeArtistTracksCarousel(
                        tracks = shownTracks,
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

            sections.recentAlbum?.takeIf {
                NativeArtistSection.RECENT_ALBUM in visibleSections
            }?.let { release ->
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
                        onClick = {
                            if (selectedItems.isNotEmpty()) {
                                onToggleSelection(CanonicalLibrarySelection(
                                    CanonicalLibraryEntityType.ALBUM, release.id,
                                ))
                            } else openedRelease = release
                        },
                        trailing = {
                            val target = CanonicalLibrarySelection(
                                CanonicalLibraryEntityType.ALBUM, release.id,
                            )
                            if (selectedItems.isNotEmpty()) {
                                IconButton(onClick = { onToggleSelection(target) }) {
                                    Icon(
                                        if (target in selectedItems) Icons.Rounded.CheckCircle
                                        else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = "Selecionar álbum",
                                    )
                                }
                            } else RowMoreButton(onClick = { onReleaseOptions(release) })
                        },
                        horizontalPadding = PAGE_GUTTER,
                    )
                }
            }
            fun section(label: String, releases: List<ReleaseEntity>) {
                if (releases.isEmpty()) return
                item(key = "artist-section-$label") { SectionHeading(label, palette) }
                item(key = "artist-cards-$label") {
                    NativeArtistReleaseCarousel(
                        releases = releases,
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
                                ) else openedRelease = release
                            },
                            onMore = { onReleaseOptions(release) },
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
