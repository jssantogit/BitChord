package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionAccess
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditorialShelf
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsShadowCatalog
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsShadowRelease
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsSourceOnlyDiscovery
import com.music.bitchord.data.catalog.metadata.discogs.loadCompleteMusicBrainzArtistGroupTitles
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SOURCE_ARTIST_MBID =
    Regex("^catalog:ARTIST:([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$")

/**
 * Fetch only after an exact, completed MusicBrainz artist discography browse.
 * Never infer 'not listed in MusicBrainz' from the first page of results.
 * Source candidates are not playable and never added to Room as fake MBIDs.
 */
@Composable
internal fun rememberDiscogsArtistSupplementaryReleases(
    artistBrowseId: String,
    artistName: String,
    musicBrainzCatalogComplete: Boolean,
    knownMusicBrainzTitles: Set<String>,
): List<DiscogsShadowRelease> {
    val artistMbid = SOURCE_ARTIST_MBID.matchEntire(artistBrowseId)?.groupValues?.get(1)
    val knownNames = remember(knownMusicBrainzTitles) { knownMusicBrainzTitles.toSet() }
    // Use the vararg-key overload; Compose exposes only key1..key3 on named overloads.
    val rows by produceState<List<DiscogsShadowRelease>>(
        emptyList(),
        artistMbid,
        musicBrainzCatalogComplete,
        knownNames,
        artistName,
    ) {
        value = emptyList()
        if (artistMbid == null ||
            !DiscogsEditionAccess.ENABLED || !DiscogsEditionAccess.isSearchConfigured()
        ) return@produceState
        value = try {
            withContext(Dispatchers.IO) {
                // The initial MusicBrainz page is not complete for large artists.
                // Never identify source-only releases until a bounded, exact
                // artist-MBID browse has checked every relevant group.
                val completedTitles = if (musicBrainzCatalogComplete) knownNames
                    else loadCompleteMusicBrainzArtistGroupTitles(artistMbid)
                        ?: return@withContext emptyList<DiscogsShadowRelease>()
                DiscogsEditionAccess.discovery.findForArtist(
                    artistName = artistName,
                    mbCatalogChecked = true,
                    knownMusicBrainzTitles = completedTitles,
                ).also { safe ->
                    // These are already compared against a complete artist
                    // catalog and carry no invented MusicBrainz identity.
                    DiscogsSourceOnlyDiscovery.store.update(
                        artistMbid, safe, System.currentTimeMillis(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    return rows
}

/**
 * The approved native artist shelf/card components receive Discogs-only
 * metadata. Their click opens source attribution, never a forged MB route,
 * playback/queue request or a duplicate provider-owned detail screen.
 */
@Composable
internal fun DiscogsArtistSupplementaryShelves(
    releases: List<DiscogsShadowRelease>,
    palette: ArtworkPalette,
) {
    if (releases.isEmpty()) return
    val uri = LocalUriHandler.current
    val grouped = remember(releases) { DiscogsShadowCatalog.shelves(releases) }
    val shelves = listOf(
        DiscogsEditorialShelf.ALBUMS to "Álbuns · Discogs",
        DiscogsEditorialShelf.EPS to "EPs · Discogs",
        DiscogsEditorialShelf.SINGLES to "Singles · Discogs",
    )
    for ((shelf, title) in shelves) {
        val items = grouped[shelf].orEmpty()
        if (items.isEmpty()) continue
        NativeArtistReleaseShelf(
            title = title,
            releases = items,
            palette = palette,
            horizontalPadding = PAGE_GUTTER,
            topPadding = 22.dp,
            itemKey = { it.key },
        ) { release ->
            NativeArtistReleaseCard(
                title = release.title,
                subtitle = "${release.year} · Discogs · só metadados",
                palette = palette,
                modifier = Modifier.width(152.dp),
                onClick = { uri.openUri(release.attributionUrl) },
                artwork = {
                    Box(Modifier.fillMaxSize()) {
                        CoverPlaceholder(Icons.Rounded.Album, Modifier.fillMaxSize())
                    }
                },
            )
        }
    }
}
