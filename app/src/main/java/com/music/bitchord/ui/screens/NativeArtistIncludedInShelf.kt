package com.music.bitchord.ui.screens

import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearance
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearancesClient
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val appearancesClient by lazy { MusicBrainzArtistAppearancesClient() }
private val appearanceCoverArt by lazy { CoverArtArchive() }

/**
 * An optional section in the SAME artist detail frame, not a parallel artist
 * page. No section is drawn until a MusicBrainz track_artist browse proves
 * that another release credits this artist on a track but not on its cover.
 */
@Composable
internal fun NativeArtistIncludedInShelf(
    artistMbid: String,
    palette: ArtworkPalette,
    onOpen: (CanonicalCatalogSearchRow) -> Unit,
) {
    val appearances by produceState<List<MusicBrainzArtistAppearance>>(
        initialValue = emptyList(), key1 = artistMbid,
    ) {
        value = try {
            withContext(Dispatchers.IO) { appearancesClient.browse(artistMbid) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
    }
    val covers by produceState(initialValue = emptyMap<String, String>(),
        key1 = appearances,
    ) {
        val verified = appearances.filter { it.releaseMbid != null }.take(6)
        value = withContext(Dispatchers.IO) {
            buildMap {
                for (entry in verified) {
                    val art = try {
                        appearanceCoverArt.findCover(entry.releaseMbid, null,
                            CoverArtSize.THUMBNAIL)
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { null }
                    if (!art.isNullOrBlank()) put(entry.releaseGroupMbid, art)
                }
            }
        }
    }
    if (appearances.isEmpty()) return
    NativeArtistReleaseShelf(
        title = "Incluído em",
        releases = appearances,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        topPadding = 22.dp,
        itemKey = { it.releaseGroupMbid },
    ) { appearance ->
        val page = CanonicalCatalogSearchRow(
            musicBrainzId = appearance.releaseGroupMbid,
            kind = CanonicalSearchKind.RELEASE_GROUP,
            title = appearance.title,
            subtitle = appearance.artistCredit,
            releaseDate = appearance.releaseDate,
            releaseKind = appearance.primaryType,
            artworkUri = covers[appearance.releaseGroupMbid],
        )
        NativeArtistReleaseCard(
            title = appearance.title,
            subtitle = appearance.artistCredit,
            palette = palette,
            onClick = { onOpen(page) },
            artwork = {
                Box(Modifier.fillMaxSize()) {
                    CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
                    covers[appearance.releaseGroupMbid]?.let { uri ->
                        AsyncImage(
                            model = uri,
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
