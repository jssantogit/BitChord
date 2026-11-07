package com.music.bitchord.ui.screens

import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.data.catalog.metadata.CoverArtArchive
import com.music.bitchord.data.catalog.metadata.CoverArtSize
import com.music.bitchord.data.catalog.metadata.RoomMusicBrainzCoverArtStore
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.canonicalStreamingTitleIsSafe
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearance
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearancesClient
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.loadCanonicalCoversProgressively
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val appearancesClient by lazy { MusicBrainzArtistAppearancesClient() }
/** A metadata-only guest credit is not proof the host album belongs to the
 * featured artist. Require exact Recording identity AND independently
 * corroborated commercial edition evidence before painting a track card. */
internal fun nativeArtistEditorialGuestTracks(
    appearances: List<MusicBrainzArtistAppearance>,
): List<MusicBrainzArtistAppearance> = appearances.asSequence()
    .filter {
        it.commerciallyVerified && !it.recordingMbid.isNullOrBlank() &&
            !it.trackTitle.isNullOrBlank() &&
            canonicalStreamingTitleIsSafe(it.trackTitle)
    }
    // One editorial song can be represented by several MusicBrainz
    // Recordings and alternate editions of the SAME host release.
    // Prefer the first verified official row, but never duplicate the card.
    // The host credit is part of the key: another artist recording a song
    // with the same title is still a different collaboration.
    .distinctBy { appearance ->
        val host = appearance.artistCredit.normalizeGuestDisplayKey()
        val song = requireNotNull(appearance.trackTitle).normalizeGuestDisplayKey()
        "$host|$song"
    }
    .distinctBy { it.recordingMbid }
    .take(12)
    .toList()

private fun String.normalizeGuestDisplayKey(): String =
    java.text.Normalizer.normalize(trim().lowercase(java.util.Locale.ROOT),
        java.text.Normalizer.Form.NFKC)
        .replace(Regex("""[\p{P}\p{S}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

/**
 * A previously verified Front-v5 for this exact ReleaseGroup is enough to
 * display its sleeve. Reusing it removes a slow CAA edition request when the
 * artist discography already warmed that group in Room.
 */
internal suspend fun nativeArtistIncludedInCoverFromVerifiedCache(
    releaseMbid: String?,
    groupMbid: String,
    cache: suspend (String) -> String?,
    fetch: suspend (String?, String) -> String?,
): String? = cache(groupMbid) ?: fetch(releaseMbid, groupMbid)

/** Only exact MusicBrainz Release/ReleaseGroup identities enter CAA.
 * Cap network work while preserving group-only sleeves from valid editions. */
internal fun nativeArtistIncludedInCoverTargets(
    appearances: List<MusicBrainzArtistAppearance>,
): List<Pair<String?, String>> =
    appearances.distinctBy { it.releaseGroupMbid }.take(10)
        .map { it.releaseMbid to it.releaseGroupMbid }

/**
 * Two independently verified guest releases can resolve their covers without
 * the first CAA timeout hiding all later thumbnails. As with the artist
 * discography, use the bounded progressive loader instead of one HTTP call
 * per row on the UI producer. The actual group MBID owns each result.
 */
internal suspend fun resolveNativeIncludedInCovers(
    appearances: List<MusicBrainzArtistAppearance>,
    lookup: suspend (String?, String) -> String?,
    onResolved: (Map<String, String>) -> Unit,
) {
    loadCanonicalCoversProgressively(
        keys = nativeArtistIncludedInCoverTargets(appearances),
        maxConcurrent = 3,
        fetch = { (releaseId, groupId) -> lookup(releaseId, groupId) },
        publish = { batch ->
            onResolved(batch.mapKeys { (target, _) -> target.second })
        },
    )
}

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
    val context = LocalContext.current.applicationContext
    val appearanceCoverArt = remember(context) {
        CoverArtArchive(store = RoomMusicBrainzCoverArtStore(
            CanonicalCatalogDatabase.get(context).catalogDao(),
        ))
    }
    val appearances by produceState<List<MusicBrainzArtistAppearance>>(
        initialValue = emptyList(), key1 = artistMbid,
    ) {
        value = try {
            withContext(Dispatchers.IO) { appearancesClient.browse(artistMbid) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
    }
    val visibleTracks = nativeArtistEditorialGuestTracks(appearances)
    val covers by produceState(initialValue = emptyMap<String, String>(),
        key1 = visibleTracks,
    ) {
        // Verified edition/group identity survives the concurrent fetch.
        // Fast CAA images appear independently of a slower first response.
        resolveNativeIncludedInCovers(
            appearances = visibleTracks,
            lookup = { releaseId, groupId ->
                withContext(Dispatchers.IO) {
                    try {
                        nativeArtistIncludedInCoverFromVerifiedCache(
                            releaseMbid = releaseId,
                            groupMbid = groupId,
                            cache = {
                                appearanceCoverArt.cachedVerifiedGroupCover(
                                    it, CoverArtSize.THUMBNAIL,
                                )
                            },
                            fetch = { edition, group ->
                                appearanceCoverArt.findCover(
                                    edition, group, CoverArtSize.THUMBNAIL,
                                )
                            },
                        )
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { null }
                }
            },
            onResolved = { batch -> value = value + batch },
        )
    }
    if (visibleTracks.isEmpty()) return
    NativeArtistReleaseShelf(
        title = "Incluído em",
        releases = visibleTracks,
        palette = palette,
        horizontalPadding = PAGE_GUTTER,
        topPadding = 22.dp,
        itemKey = { it.recordingMbid ?: it.releaseGroupMbid },
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
            title = appearance.trackTitle ?: appearance.title,
            subtitle = appearance.artistCredit + " · " + appearance.title,
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
