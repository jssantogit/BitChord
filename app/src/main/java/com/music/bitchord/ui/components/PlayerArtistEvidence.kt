package com.music.bitchord.ui.components

import android.content.Context
import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalArtistCreditRow
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.ui.screens.playlistDisplayArtistGroupId
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.androidCanonicalQueueResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Source provenance is part of artist navigation, not inferred from a name. */
internal data class SourceOwnedArtistEvidence(
    val artistId: String,
    val artistName: String,
    val providerKey: String,
)

internal fun verifiedSourceOwnedPlayerArtist(
    recordingId: String,
    providerKey: String,
    credits: List<CanonicalArtistCreditRow>,
    availableArtists: List<ArtistEntity>,
    artistName: String? = null,
): SourceOwnedArtistEvidence? {
    if (recordingId.isBlank() || providerKey.isBlank()) return null
    val lead = credits.filter { it.position == 0 && it.artistId.isNotBlank() }
        .distinctBy { it.artistId }
    // Prefer the exact credited ArtistId. When a migrated local/provider
    // recording lacks credits, accept only ONE artist in that *same provider*
    // with the exact displayed name. Never resolve conflicting lead credits
    // by fuzzy name or borrow an artist from another source.
    val actual = when {
        lead.size == 1 -> availableArtists.filter { it.id == lead.single().artistId }.singleOrNull()
        lead.isEmpty() && !artistName.isNullOrBlank() ->
            availableArtists.filter { it.name.equals(artistName, ignoreCase = true) }
                .singleOrNull()
        else -> null
    } ?: return null
    return SourceOwnedArtistEvidence(actual.id, actual.name, providerKey)
}

/** Local browse-only artist groups have typed IDs, not invented ArtistIds.
 * Confirm the current recording is a member of that exact MediaStore group. */
internal fun verifiedLocalDisplayArtist(
    recordingId: String,
    providerKey: String,
    displayName: String,
    memberRecordingIds: List<String>,
): SourceOwnedArtistEvidence? {
    val name = displayName.trim()
    if (providerKey != CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER || recordingId.isBlank() ||
        name.isBlank() || name != displayName || recordingId !in memberRecordingIds
    ) return null
    return SourceOwnedArtistEvidence(playlistDisplayArtistGroupId(name), name, providerKey)
}

/** The Library already grouped these locally scanned records by artist label.
 * A sourceKey must exactly equal this song's MediaStore URI; otherwise a
 * similar name on another phone/server is not evidence of identity. */
internal fun verifiedLocalMediaAssetArtist(
    requestedUri: String?,
    verifiedSourceKey: String?,
    recordingId: String,
    displayName: String,
    memberRecordingIds: List<String>,
): SourceOwnedArtistEvidence? {
    if (requestedUri.isNullOrBlank() || requestedUri != verifiedSourceKey) return null
    return verifiedLocalDisplayArtist(
        recordingId = recordingId,
        providerKey = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
        displayName = displayName,
        memberRecordingIds = memberRecordingIds,
    )
}

/** A local Release credit is a real Library display group only when EVERY
 * currently scoped member is in the same artist name group. Compilation
 * albums and multi-artist collaborations never silently jump to the wrong
 * Artist detail. These members are prefiltered for the same provider. */
internal fun verifiedLocalAlbumDisplayArtist(
    providerKey: String,
    albumCredit: String,
    memberArtistNames: List<String>,
): SourceOwnedArtistEvidence? {
    val name = albumCredit.trim()
    if (providerKey != CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER ||
        name.isBlank() || name != albumCredit || memberArtistNames.isEmpty() ||
        memberArtistNames.any { it != name }
    ) return null
    return SourceOwnedArtistEvidence(playlistDisplayArtistGroupId(name), name, providerKey)
}

/** Local source ownership must stay in the Library. During asynchronous
 * artist verification an apparent "Search artist" action could still win a
 * tap; avoid leaving the app's own local artist route entirely. */
internal fun shouldOfferFallbackArtistSearch(localUri: String?, artist: String): Boolean =
    localUri.isNullOrBlank() && artist.isNotBlank()

/** Exact Apple route format used by the iTunes catalog; no display-name search. */
internal fun verifiedAppleArtistBrowseRoute(raw: String?): String? =
    raw?.takeIf { Regex("^itunes:[a-z]{2}:artist:[1-9][0-9]*$").matches(it) }

/** A provider-native artist key is not a legacy YouTube artist URL. */
internal fun verifiedLegacyPlayerArtistBrowseId(value: String?): String? =
    value?.takeIf { it.startsWith("UC") && it.length >= 20 }
        ?: value?.takeIf {
            Regex("^catalog:ARTIST:[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(it)
        }


/**
 * The tappable credit on Now Playing is a navigation request, not a search.
 * Either open the exact artist that owns the current recording in the current
 * source, or a typed catalog link that was actually attached to that credit.
 * A free-text artist name is never enough to cross the source boundary.
 */
internal sealed interface PlayerArtistTapRoute {
    data class SourceOwned(val artist: SourceOwnedArtistEvidence) : PlayerArtistTapRoute
    data class Catalog(val browseId: String, val artistName: String) : PlayerArtistTapRoute
}

internal fun planPlayerArtistTapRoute(
    song: Song,
    clickedId: String?,
    clickedName: String,
    sourceOwned: SourceOwnedArtistEvidence?,
): PlayerArtistTapRoute? {
    val name = clickedName.trim()
    if (name.isBlank()) return null

    // The provider's verified recording + artist relation takes precedence over
    // any stale Song.artistId. Do not send a second credit to the lead artist.
    if (sourceOwned != null) {
        return sourceOwned.takeIf {
            it.artistName.equals(name, ignoreCase = true) &&
                it.artistId.isNotBlank() && it.providerKey.isNotBlank()
        }?.let(PlayerArtistTapRoute::SourceOwned)
    }

    // An unindexed local/remote queue entry cannot be turned into an online
    // artist just because the parser retained an Apple/catalog identifier.
    val nativeLocator = song.localUri != null || song.localPath != null ||
        song.videoId.startsWith("content://", ignoreCase = true) ||
        song.videoId.startsWith("file://", ignoreCase = true) ||
        song.videoId.startsWith("bitchord://", ignoreCase = true)
    if (nativeLocator) return null

    val route = verifiedAppleArtistBrowseRoute(clickedId)
        ?: verifiedLegacyPlayerArtistBrowseId(clickedId)
            ?.takeIf { it.startsWith("catalog:ARTIST:") }
        ?: return null
    val creditAttachedToSong =
        (clickedId == song.artistId && song.artist.equals(name, ignoreCase = true)) ||
            song.artists.any { credit ->
                credit.browseId == route && credit.name.equals(name, ignoreCase = true)
            }
    return PlayerArtistTapRoute.Catalog(route, name)
        .takeIf { creditAttachedToSong }
}

/**
 * Only a queue identity that resolves to one current MediaAsset may open an
 * artist inside the same provider. Avoid crossing local files and Navidrome.
 */
internal suspend fun verifiedPlayerSourceOwnedArtist(
    context: Context,
    song: Song,
): SourceOwnedArtistEvidence? = withContext(Dispatchers.IO) {
    val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
    try {
        // A local file already has an exact, persisted MediaStore sourceKey.
        // Resolve it BEFORE inspecting the queue: an old queue item may have
        // no canonical playback identity while its Library artist exists.
        song.localUri?.takeIf(String::isNotBlank)?.let { uri ->
            val provider = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
            val asset = dao.mediaAsset(provider, uri)
            if (asset != null) {
                val proven = verifiedSourceOwnedPlayerArtist(
                    recordingId = asset.recordingId,
                    providerKey = provider,
                    credits = dao.recordingArtistCredits(asset.recordingId),
                    availableArtists = dao.providerScopedArtists(provider),
                    artistName = song.artist,
                ) ?: verifiedLocalMediaAssetArtist(
                    requestedUri = uri,
                    verifiedSourceKey = asset.sourceKey,
                    recordingId = asset.recordingId,
                    displayName = song.artist,
                    memberRecordingIds = dao.playlistDisplayArtistRecordingIds(
                        provider, song.artist,
                    ),
                )
                if (proven != null) return@withContext proven
            }
        }
        // Nonlocal/remote tracks still require their queue identity to prove
        // ownership; never guess a remote ArtistId using metadata alone.
        val resolver = androidCanonicalQueueResolver(context.applicationContext)
        val identity = resolver.identifyAll(listOf(song)).singleOrNull()
            ?: return@withContext null
        val source = identity.providerKey?.takeIf(String::isNotBlank)
            ?: return@withContext null
        if (resolver.resolveAll(listOf(identity)).singleOrNull()?.identity != identity) {
            return@withContext null
        }
        verifiedSourceOwnedPlayerArtist(
            recordingId = identity.recordingId,
            providerKey = source,
            credits = dao.recordingArtistCredits(identity.recordingId),
            availableArtists = dao.providerScopedArtists(source),
            artistName = song.artist,
        ) ?: if (source == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER) {
            verifiedLocalDisplayArtist(
                recordingId = identity.recordingId,
                providerKey = source,
                displayName = song.artist,
                memberRecordingIds = dao.playlistDisplayArtistRecordingIds(source, song.artist),
            )
        } else null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
