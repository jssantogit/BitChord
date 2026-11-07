package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.catalog.metadata.ListenBrainzSimilarArtist
import com.music.bitchord.data.catalog.metadata.ListenBrainzSimilarArtistsClient
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

private val similarArtistClient by lazy { ListenBrainzSimilarArtistsClient() }

/** Artist identity art is round; release/track sleeves remain square. */
internal val nativeArtistSimilarArtworkShape = CircleShape

/** Verified MBIDs are the identity and page target; name-only Apple art is
 * optional decoration, never evidence for merging artist identities. */
internal fun nativeArtistSimilarPhotoTargets(
    artists: List<ListenBrainzSimilarArtist>,
): List<ListenBrainzSimilarArtist> =
    artists.distinctBy { it.mbid.lowercase(Locale.ROOT) }.take(8)

internal fun nativeArtistSimilarPortraitUri(url: String?): String? {
    if (url.isNullOrBlank() || url.length > 2_000) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    return url.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "mzstatic.com" || host.endsWith(".mzstatic.com")) &&
            uri.userInfo == null && !uri.path.isNullOrBlank()
    }
}

/** Only two optional Apple portrait lookups are in flight at once.
 * Publish each verified cover independently, so a slow first result does not
 * delay other artists. No name-based identity merge or persisted association. */
internal suspend fun resolveNativeSimilarPortraits(
    artists: List<ListenBrainzSimilarArtist>,
    lookup: suspend (String) -> String?,
    onResolved: (String, String) -> Unit,
) = coroutineScope {
    val permits = Semaphore(2)
    artists.map { candidate ->
        launch {
            val url = try {
                permits.withPermit { lookup(candidate.name) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            nativeArtistSimilarPortraitUri(url)?.let { onResolved(candidate.mbid, it) }
        }
    }.joinAll()
}

/** Native artist shelf with actual ListenBrainz identity evidence and optional
 * exact-name Apple artist artwork, reusing the existing artist hero resolver. */
@Composable
internal fun NativeArtistSimilarShelf(
    artistMbid: String,
    palette: ArtworkPalette,
    onOpen: (CanonicalCatalogSearchRow) -> Unit,
) {
    val related by produceState<List<ListenBrainzSimilarArtist>>(
        initialValue = emptyList(), key1 = artistMbid,
    ) {
        value = try {
            withContext(Dispatchers.IO) { similarArtistClient.load(artistMbid) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
    }
    val visible = nativeArtistSimilarPhotoTargets(related)
    val artwork by produceState<Map<String, String>>(
        initialValue = emptyMap(), key1 = visible,
    ) {
        // AppleArtistArtRepository already validates exact-name responses.
        // Resolve a bounded number in parallel, publishing each portrait as
        // it arrives, even when a previous Apple request is slow.
        resolveNativeSimilarPortraits(
            artists = visible,
            lookup = { name ->
                withContext(Dispatchers.IO) {
                    AppleArtistArtRepository.artFor(name)?.heroUrl
                }
            },
            onResolved = { mbid, portrait -> value = value + (mbid to portrait) },
        )
    }
    if (visible.isEmpty()) return
    NativeArtistReleaseShelf(
        title = "Artistas semelhantes",
        releases = visible,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        topPadding = 22.dp,
        itemKey = { it.mbid },
    ) { artist ->
        val page = CanonicalCatalogSearchRow(
            musicBrainzId = artist.mbid,
            kind = CanonicalSearchKind.ARTIST,
            title = artist.name,
            subtitle = "",
        )
        NativeArtistReleaseCard(
            title = artist.name,
            subtitle = "Artista",
            palette = palette,
            onClick = { onOpen(page) },
            artworkShape = nativeArtistSimilarArtworkShape,
            artwork = {
                Box(Modifier.fillMaxSize()) {
                    CoverPlaceholder(Icons.Rounded.Person, Modifier.fillMaxSize())
                    artwork[artist.mbid]?.let { portrait ->
                        AsyncImage(
                            model = portrait,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
        )
    }
}
