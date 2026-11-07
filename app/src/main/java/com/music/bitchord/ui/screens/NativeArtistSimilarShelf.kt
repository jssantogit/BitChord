package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.metadata.ListenBrainzSimilarArtist
import com.music.bitchord.data.catalog.metadata.ListenBrainzSimilarArtistsClient
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val similarArtistClient by lazy { ListenBrainzSimilarArtistsClient() }

/** Native artist context section sourced from actual ListenBrainz radio links. */
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
    if (related.isEmpty()) return
    NativeArtistReleaseShelf(
        title = "Artistas semelhantes",
        releases = related,
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
            artwork = { CoverPlaceholder(Icons.Rounded.Person, Modifier.fillMaxSize()) },
        )
    }
}
