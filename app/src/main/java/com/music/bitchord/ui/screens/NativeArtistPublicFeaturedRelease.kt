package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.ui.canonicalEditorialReleaseType
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.theme.ArtworkPalette

/** Same approved hero card used by Library artists, with public catalog metadata. */
@Composable
internal fun NativeArtistPublicFeaturedRelease(
    release: CanonicalCatalogSearchRow,
    palette: ArtworkPalette,
    onOpen: (CanonicalCatalogSearchRow) -> Unit,
) {
    val label = when (canonicalEditorialReleaseType(release)) {
        "Single" -> stringResource(R.string.recent_single)
        "EP" -> stringResource(R.string.recent_ep)
        else -> stringResource(R.string.recent_album)
    }
    NativeArtistTopReleaseCard(
        title = release.title,
        subtitle = canonicalArtistReleaseDateLabel(release.releaseDate)
            ?: release.subtitle.orEmpty(),
        label = label,
        palette = palette,
        onClick = { onOpen(release) },
        artwork = {
            Box(Modifier.fillMaxSize()) {
                CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
                release.artworkUri?.takeIf { it.isNotBlank() }?.let { art ->
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
    )
}
