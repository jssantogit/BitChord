package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.keyColors
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.NUMBERED_ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.flow.collectLatest

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

    // The existing star edits the artist's first-party rating. Local
    // display-only groups use DISPLAY_ARTIST, never a fabricated MusicBrainz
    // ArtistId; the save path checks real provider-scoped song membership.
    val artistRatingKey = artistId?.let { id ->
        canonicalEntityRatingTarget(
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, id),
        )
    }
    val artistRated by produceState(initialValue = false, key1 = artistRatingKey) {
        val key = artistRatingKey ?: return@produceState
        ListeningEventDatabase.get(context).canonicalEntityRatingDao()
            .observe(key.entityType, key.entityId).collectLatest { value = it != null }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var openedRelease by remember(artistName) { mutableStateOf<ReleaseEntity?>(null) }
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
        val albumIds = canonicalArtistReleaseTrackIds(
            availableLibraryTracks.map { it.recordingId }, releaseRecordingIds.toSet(),
        ).toSet()
        val albumRunningOrder = canonicalAlbumDetailTrackOrder(
            availableLibraryTracks.filter { it.recordingId in albumIds },
            releaseRecordingIds,
        )
        val albumTracks = canonicalAlbumDetailSort(albumRunningOrder, albumSort)
        val albumTrackNumbers = canonicalAlbumDetailTrackNumbers(albumRunningOrder)
        val visibleAlbumTracks = albumTracks.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                it.artistDisplay.contains(query, ignoreCase = true)
        }
        val trustedEvidence = nativeReleases.evidence[selectedAlbum.id]
        val albumRating by produceState<Double?>(initialValue = null, key1 = selectedAlbum.id) {
            ListeningEventDatabase.get(context).canonicalEntityRatingDao()
                .observe("ALBUM", selectedAlbum.id).collectLatest { saved ->
                    value = saved?.ratingValue?.takeIf(::isValidCanonicalEntityRating)
                }
        }
        val albumQuality = canonicalAlbumSourceQuality(albumRunningOrder, selectedSourceKey)
        val albumFooter = canonicalAlbumFooterMetadata(
            trustedEvidence?.firstReleaseDate, albumRunningOrder,
        )
        val releasePalette = rememberArtworkPalette(
            imageUrl = canonicalNativeReleasePaletteUri(
                nativeReleases.artwork[selectedAlbum.id]?.artworkUri,
                selectedAlbum.artworkUri,
            ),
        )
        BoxWithConstraints(Modifier.fillMaxSize().background(releasePalette.background)) {
            val sleeve = (maxWidth * 0.76f).coerceAtMost(maxHeight * 0.6f)
            LazyColumn(contentPadding = PaddingValues(bottom = 112.dp)) {
                item {
                    NativeReleaseHeaderLayout(
                        title = canonicalAlbumDisplayTitle(
                            selectedAlbum.title, trustedEvidence?.primaryType,
                        ),
                        credit = selectedAlbum.artistDisplay.orEmpty(),
                        meta = "",
                        rating = albumRating,
                        quality = albumQuality,
                        onRate = onRateRelease?.let { editor -> { editor(selectedAlbum) } },
                        palette = releasePalette,
                        sleeveSize = sleeve,
                        artwork = {
                            val art = nativeReleases.artwork[selectedAlbum.id]
                            if (art != null) CanonicalUnifiedArtwork(
                                art, artworkLoader, Modifier.fillMaxSize(),
                            ) else CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
                        },
                        actions = {
                            if (albumTracks.isNotEmpty()) {
                                Spacer(Modifier.height(18.dp))
                                NativeReleaseActionRow(
                                    onPlay = { onPlayAll(albumTracks, false) },
                                    onShuffle = { onPlayAll(albumTracks, true) },
                                    isLocal = false,
                                    reservePinAction = true,
                                    isPinnedInLibrary = CanonicalLibrarySelection(
                                        CanonicalLibraryEntityType.ALBUM, selectedAlbum.id,
                                    ) in libraryPins.map { it.selection },
                                    onTogglePin = {
                                        pinRepository.toggle(CanonicalLibraryPin(
                                            CanonicalLibrarySelection(
                                                CanonicalLibraryEntityType.ALBUM, selectedAlbum.id,
                                            ),
                                            selectedAlbum.title,
                                            selectedAlbum.artistDisplay.orEmpty(),
                                        ))
                                    },
                                )
                            }
                        },
                    )
                }
                if (shouldShowNativeArtistSearch(searching, query)) {
                    item(key = "nested-release-search") {
                        DetailSearchField(
                            query = query,
                            onQueryChange = onQueryChange,
                            onClose = { onQueryChange("") },
                            autoFocus = searching,
                            onFocused = {},
                            palette = releasePalette,
                        )
                    }
                }
                if (visibleAlbumTracks.isEmpty() && albumTracks.isNotEmpty()) {
                    item(key = "nested-release-no-results") {
                        Text(
                            "Nenhuma música corresponde à pesquisa.",
                            modifier = Modifier.padding(PAGE_GUTTER),
                        )
                    }
                }
                itemsIndexed(visibleAlbumTracks, key = { _, track -> track.recordingId }) { index, track ->
                    CanonicalTrackRow(
                        track = track,
                        artworkLoader = artworkLoader,
                        onClick = { onTrackPlay(track) },
                        onEditRating = { onTrackEditRating(track) },
                        onOptions = { onTrackOptions(track) },
                        albumTrackNumber = albumTrackNumbers[track.recordingId] ?: index + 1,
                        palette = releasePalette,
                    )
                    if (index < visibleAlbumTracks.lastIndex) HorizontalDivider(
                        modifier = Modifier.padding(start = NUMBERED_ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = releasePalette.divider,
                    )
                }
                item(key = "artist-nested-album-footer") {
                    NativeAlbumMetadataFooter(
                        metadata = albumFooter,
                        palette = releasePalette,
                    )
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
        PageBackground(
            page = null,
            palette = palette,
            canvas = null,
            artHeight = photoHeight,
            listState = listState,
            // Never stretch a song/album sleeve into a claimed artist photo.
            heroUrl = artistPhotoUrl,
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
            if (shownTracks.isNotEmpty()) {
                item(key = "artist-actions") {
                    // The native artist page uses three circular buttons,
                    // not the wide album/folder action pills.
                    ActionRow(
                        onPlay = { onPlayAll(shownTracks, false) },
                        onShuffle = { onPlayAll(shownTracks, true) },
                        onRateArtist = if (artistRatingKey != null)
                            onRateArtist else null,
                        artistRated = artistRated,
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
            if (shownTracks.isNotEmpty()) {
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
                        Box(Modifier.width(152.dp)) {
                            NativeLibraryEntityCard(
                                title = release.title,
                                subtitle = release.artistDisplay.orEmpty(),
                                imageUrl = null,
                                isArtist = false,
                                artwork = nativeReleases.artwork[release.id]?.let { track ->
                                    { CanonicalUnifiedArtwork(track, artworkLoader,
                                        Modifier.fillMaxSize()) }
                                },
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
                            )
                        }
                    }
                }
            }
            section("Álbuns", sections.albums)
            section("Singles e EPs", sections.singlesAndEps)
            section("Lançamentos", sections.unclassified)
        }
    }
}
