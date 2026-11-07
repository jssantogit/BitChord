package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.ui.canonicalEditorialReleaseType
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.theme.ArtworkPalette

private val featuredCoverArchive by lazy { CoverArtArchive() }
private val FEATURED_GROUP_ID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

/** Only an identified MusicBrainz release group can be queried for art. */
internal fun nativeArtistFeaturedReleaseCoverTarget(
    release: CanonicalCatalogSearchRow,
): String? = release.musicBrainzId.takeIf {
    release.kind == CanonicalSearchKind.RELEASE_GROUP &&
        FEATURED_GROUP_ID.matches(it)
}

/** Same approved hero card used by Library artists, with public catalog metadata. */
@Composable
internal fun NativeArtistPublicFeaturedRelease(
    release: CanonicalCatalogSearchRow,
    palette: ArtworkPalette,
    onOpen: (CanonicalCatalogSearchRow) -> Unit,
) {
    val resolvedCover by produceState(
        initialValue = release.artworkUri?.takeIf(String::isNotBlank),
        key1 = release.musicBrainzId,
        key2 = release.artworkUri,
    ) {
        if (!value.isNullOrBlank()) return@produceState
        val verifiedGroup = nativeArtistFeaturedReleaseCoverTarget(release)
            ?: return@produceState
        val fetched = try {
            withContext(Dispatchers.IO) {
                featuredCoverArchive.findCover(
                    null, verifiedGroup, CoverArtSize.THUMBNAIL,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        if (!fetched.isNullOrBlank()) value = fetched
    }
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
                resolvedCover?.let { art ->
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
