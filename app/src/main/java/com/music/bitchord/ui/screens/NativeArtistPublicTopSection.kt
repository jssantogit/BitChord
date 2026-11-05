package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * Reuses the existing native artist shelf AND the existing 52dp search row.
 * No Song, videoId, play, queue, rating or provider-specific detail renderer.
 * Only an exact canonical artist/recording identity can be tapped.
 */
@Composable
internal fun NativeArtistPublicTopSection(
    artistMbid: String,
    rows: List<CanonicalCatalogSearchRow>,
    palette: ArtworkPalette,
    onRecordingOpen: (CanonicalCatalogSearchRow) -> Unit,
) {
    val exact = rows.filter {
        it.kind == CanonicalSearchKind.RECORDING &&
            it.artistScopeMbid?.equals(artistMbid, ignoreCase = true) == true
    }
    NativeArtistTracksShelf(
        title = stringResource(R.string.top_songs),
        tracks = exact,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        itemKey = { it.musicBrainzId },
    ) { recording ->
        NativeSearchResultRow(
            row = NativeSearchRowUi(
                title = recording.title,
                subtitle = recording.subtitle.orEmpty(),
                artworkUri = recording.artworkUri,
                artistPortrait = false,
            ),
            onClick = { onRecordingOpen(recording) },
            horizontalPadding = 0.dp,
        )
    }
}
