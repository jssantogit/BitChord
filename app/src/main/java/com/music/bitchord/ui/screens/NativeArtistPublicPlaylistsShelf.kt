package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistClient
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val verifiedArtistPlaylistClient by lazy { ListenBrainzPublicPlaylistClient() }

/** Artist playlist shelf with actual recording membership, not title-only fuzzy matches. */
@Composable
internal fun NativeArtistPublicPlaylistsShelf(
    artistMbid: String,
    artistName: String,
    verifiedRecordingMbids: Set<String>,
    palette: ArtworkPalette,
    onOpen: (ListenBrainzPublicPlaylist) -> Unit,
) {
    val lists by produceState<List<ListenBrainzPublicPlaylist>>(
        initialValue = emptyList(), key1 = artistMbid, key2 = verifiedRecordingMbids,
    ) {
        value = try {
            withContext(Dispatchers.IO) {
                verifiedArtistPlaylistClient.searchArtistVerified(
                    artistName, verifiedRecordingMbids,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
    }
    if (lists.isEmpty()) return
    NativeArtistReleaseShelf(
        title = "Playlists",
        releases = lists,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        topPadding = 22.dp,
        itemKey = { it.mbid },
    ) { playlist ->
        NativeArtistReleaseCard(
            title = playlist.title,
            subtitle = playlist.creator,
            palette = palette,
            onClick = { onOpen(playlist) },
            artwork = {
                Box(Modifier.fillMaxSize()) {
                    CoverPlaceholder(Icons.Rounded.LibraryMusic, Modifier.fillMaxSize())
                    AsyncImage(
                        model = playlist.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }
}
