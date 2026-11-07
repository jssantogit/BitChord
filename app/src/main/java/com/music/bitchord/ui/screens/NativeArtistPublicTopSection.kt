package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette

/**
 * Online rank and Library tracks share the native track row geometry. The
 * online adapter never promotes a Recording MBID into a playable asset or
 * invents a three-dot/rating capability without a Library recording.
 */
@Composable
internal fun NativeArtistPublicTopSection(
    artistMbid: String,
    rows: List<CanonicalCatalogSearchRow>,
    palette: ArtworkPalette,
    sampled: Boolean = false,
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
        supportingText = if (sampled)
            "Amostra de gravações com contagens verificadas" else null,
    ) { recording ->
        NativeDetailTrackRowLayout(
            onClick = { onRecordingOpen(recording) },
            horizontalInset = 0.dp,
            leading = {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.size(52.dp).clip(shape)
                        .background(palette.onBackground.copy(alpha = 0.08f)),
                ) {
                    CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                    recording.artworkUri?.takeIf(String::isNotBlank)?.let { art ->
                        AsyncImage(
                            model = art.artworkAt(ROW_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
            title = {
                Text(
                    text = recording.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    text = recording.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            rating = {},
            showRatingSlot = false,
        )
    }
}
