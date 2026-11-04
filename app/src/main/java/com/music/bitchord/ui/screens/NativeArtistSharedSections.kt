package com.music.bitchord.ui.screens

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * Presentation context is not provider identity: both MediaStore and
 * Navidrome are Library contexts; MusicBrainz and future metadata providers
 * are online catalog contexts. A source can have some capabilities absent.
 */
internal enum class NativeArtistContext { LIBRARY, ONLINE_CATALOG }

/**
 * Exactly one track section may be visible. `TOP_TRACKS` means an upstream
 * provider supplied an actual ordered popularity/ranking feed; a MusicBrainz
 * discography or a set of locally available assets is not popularity evidence.
 */
internal enum class NativeArtistSection {
    TRACKS,
    TOP_TRACKS,
    RECENT_ALBUM,
    ALBUMS,
    SINGLES_EPS,
    RELEASES,
}

internal data class NativeArtistSectionEvidence(
    val context: NativeArtistContext,
    val hasTracks: Boolean,
    val hasVerifiedPopularity: Boolean = false,
    val hasRecentAlbum: Boolean = false,
    val hasAlbums: Boolean = false,
    val hasSinglesAndEps: Boolean = false,
    val hasUnclassifiedReleases: Boolean = false,
)

/**
 * One evidence-gated section policy for every artist detail entry point.
 * No headline appears unless its own content exists, and library artists
 * never display a 'Mais tocadas' heading without a provider ranking source.
 *
 * The online catalog can use "Faixas" for actual non-ranked recordings,
 * but must not relabel those recordings as "Mais tocadas" by inference.
 */
internal fun nativeArtistVisibleSections(
    evidence: NativeArtistSectionEvidence,
): Set<NativeArtistSection> = buildSet {
    if (evidence.hasTracks) {
        add(
            if (evidence.context == NativeArtistContext.ONLINE_CATALOG &&
                evidence.hasVerifiedPopularity
            ) NativeArtistSection.TOP_TRACKS else NativeArtistSection.TRACKS,
        )
    }
    if (evidence.hasRecentAlbum) add(NativeArtistSection.RECENT_ALBUM)
    if (evidence.hasAlbums) add(NativeArtistSection.ALBUMS)
    if (evidence.hasSinglesAndEps) add(NativeArtistSection.SINGLES_EPS)
    if (evidence.hasUnclassifiedReleases) add(NativeArtistSection.RELEASES)
}

/**
 * Exact native artist screen structure extracted from DetailScreen:
 * one photo wash beneath one scrolling list. The artist header, actions,
 * ratings, filters, albums and menus remain caller-provided native sections.
 *
 * Provider identity and playback do not belong in this frame. The original
 * DetailScreen and the unified Library both use it with their existing
 * section lists, making future migration additive rather than a redesign.
 */
@Composable
internal fun NativeArtistDetailFrame(
    palette: ArtworkPalette,
    artHeight: Dp,
    listState: LazyListState,
    contentPadding: PaddingValues,
    page: DetailPage? = null,
    canvas: CanvasArtwork? = null,
    heroUrl: String? = null,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(palette.wash)) {
        PageBackground(
            page = page,
            palette = palette,
            canvas = canvas,
            artHeight = artHeight,
            listState = listState,
            heroUrl = heroUrl,
            modifier = Modifier.matchParentSize(),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

private const val ARTIST_TRACK_LIMIT = 20
private const val ARTIST_TRACK_ROWS_PER_COLUMN = 4

/**
 * Artist carousel geometry comes from the existing DetailScreen, not a
 * separate, source-specific implementation. Filtering and playback identity
 * remain the caller's responsibility.
 */
internal fun <T> nativeArtistTrackColumns(tracks: List<T>): List<List<T>> =
    tracks.take(ARTIST_TRACK_LIMIT).chunked(ARTIST_TRACK_ROWS_PER_COLUMN)

/**
 * One visual track strip shared by DetailScreen and the unified Library.
 * Callers provide their own row, so canonical RecordingIds need never be
 * converted into fake Song.videoIds to preserve ratings, menus or selection.
 */
@Composable
internal fun <T> NativeArtistTracksCarousel(
    tracks: List<T>,
    horizontalPadding: Dp = PAGE_GUTTER,
    itemKey: ((T) -> Any)? = null,
    row: @Composable (T) -> Unit,
) {
    if (tracks.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = trackColumnWidth(maxWidth)
        val scrollState = rememberLazyListState()
        // Bounded to 20 tracks; do not compare a potentially huge Library list
        // on each recomposition just to render its first five columns.
        val columns = nativeArtistTrackColumns(tracks)
        val columnKey: ((List<T>) -> Any)? = itemKey?.let { key ->
            { column: List<T> -> key(column.first()) }
        }
        LazyRow(
            state = scrollState,
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = rememberSnapFlingBehavior(lazyListState = scrollState),
        ) {
            items(columns, key = columnKey) { column ->
                Column(Modifier.width(columnWidth)) {
                    column.forEach { track -> row(track) }
                }
            }
        }
    }
}

/**
 * Album/single shelves use one native horizontal layout. The caller still
 * owns individual cards and their menus, pin, rating and selection actions.
 * Library can show every verified release; online shelves retain their own
 * existing 'Show all' presentation limit.
 */
@Composable
internal fun <T> NativeArtistReleaseCarousel(
    releases: List<T>,
    horizontalPadding: Dp = PAGE_GUTTER,
    itemSpacing: Dp = 14.dp,
    itemKey: ((T) -> Any)? = null,
    card: @Composable (T) -> Unit,
) {
    if (releases.isEmpty()) return
    val scrollState = rememberLazyListState()
    LazyRow(
        state = scrollState,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = scrollState),
    ) {
        items(releases, key = itemKey) { release -> card(release) }
    }
}
