package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicArtworkLoader
import com.music.bitchord.ui.components.CoverPlaceholder

/**
 * One presentation policy for a verified ReleaseEntity's cover, both from the
 * root Library and from the artist's discography. A scoped track's artwork
 * loader remains authoritative for provider-authenticated artwork.
 *
 * Only a real local file/content URI or HTTP(S) release URI may be given to
 * Coil directly; an arbitrary provider key is never converted into a URL.
 * We do not submit remote source-authenticated links to the palette decoder.
 */
internal enum class NativeAlbumHeroSource { DIRECT_URI, TRACK_LOADER, PLACEHOLDER }

internal fun nativeAlbumHeroSource(
    releaseArtworkUri: String?,
    hasTrackArtwork: Boolean,
): NativeAlbumHeroSource {
    val uri = releaseArtworkUri?.trim().orEmpty()
    val local = uri.startsWith("content://", ignoreCase = true) ||
        uri.startsWith("file://", ignoreCase = true)
    val remote = uri.startsWith("https://", ignoreCase = true) ||
        uri.startsWith("http://", ignoreCase = true)
    return when {
        local -> NativeAlbumHeroSource.DIRECT_URI
        hasTrackArtwork -> NativeAlbumHeroSource.TRACK_LOADER
        remote -> NativeAlbumHeroSource.DIRECT_URI
        else -> NativeAlbumHeroSource.PLACEHOLDER
    }
}

/** Preserve a placeholder under the remote art until it actually paints. */
@Composable
internal fun NativeCanonicalAlbumArtwork(
    releaseArtworkUri: String?,
    artworkTrack: CanonicalBrowseTrack?,
    artworkLoader: OpenSubsonicArtworkLoader,
) {
    Box(Modifier.fillMaxSize()) {
        CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
        when (nativeAlbumHeroSource(
            releaseArtworkUri = releaseArtworkUri,
            hasTrackArtwork = artworkTrack != null,
        )) {
            NativeAlbumHeroSource.DIRECT_URI -> AsyncImage(
                model = releaseArtworkUri?.trim(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            NativeAlbumHeroSource.TRACK_LOADER -> artworkTrack?.let { track ->
                CanonicalUnifiedArtwork(track, artworkLoader, Modifier.fillMaxSize())
            }
            NativeAlbumHeroSource.PLACEHOLDER -> Unit
        }
    }
}
