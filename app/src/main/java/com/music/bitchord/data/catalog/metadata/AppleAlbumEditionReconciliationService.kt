package com.music.bitchord.data.catalog.metadata

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Bounded candidates gathered from MusicBrainz; never products to display. */
internal data class AppleAlbumEditionEvidence(
    val editions: List<MusicBrainzCatalogReleaseDetail>,
    val groups: List<MusicBrainzReleaseGroupHit>,
)

internal fun interface AppleAlbumEditionEvidenceSource {
    suspend fun discover(album: ItunesEditorialRelease): AppleAlbumEditionEvidence?
}

/**
 * Network-side candidate discovery. The barcode indexes possible MB Releases,
 * not a verified crosswalk. Missing, truncated or malformed search results
 * cannot certify that a better matching edition was not omitted.
 */
internal class MusicBrainzBarcodeEditionEvidenceSource(
    private val search: MusicBrainzCatalogSearch = MusicBrainzCatalogSearch(),
    private val graph: MusicBrainzCatalogGraph = MusicBrainzCatalogGraph(),
) : AppleAlbumEditionEvidenceSource {
    private val suffix = Regex("""\s[-–—]\s(?:EP|Single)$""", RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    private fun normalized(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace(space, " ").lowercase(Locale.ROOT)

    override suspend fun discover(album: ItunesEditorialRelease): AppleAlbumEditionEvidence? {
        val barcode = album.upc?.takeIf { Regex("^[0-9]{8,14}$").matches(it) }
            ?: return null
        val searchResult = search.searchReleasesByBarcode(barcode, limit = 12)
        val page = (searchResult as? MusicBrainzCatalogSearchResult.Success)
            ?.page ?: return null
        // A filtered, incomplete or capped page cannot establish uniqueness.
        if (page.offset != 0 || page.total !in 1..12 ||
            page.consumedCount != page.total || page.items.size != page.total
        ) return null
        val appleTitle = normalized(album.title.replace(suffix, ""))
        val appleArtist = normalized(album.artistName)
        val relevant = page.items.filter { hit ->
            isCuratedMusicBrainzRelease(hit) &&
                normalized(hit.title) == appleTitle &&
                normalized(hit.artistDisplay.orEmpty()) == appleArtist
        }
        if (relevant.isEmpty() || relevant.size > 8 ||
            relevant.any { it.artistCreditMbids.isNullOrEmpty() }
        ) return null

        val editions = ArrayList<MusicBrainzCatalogReleaseDetail>(relevant.size)
        for (hit in relevant) {
            val lookup = graph.lookupRelease(hit.musicBrainzId)
            val detail = (lookup as? MusicBrainzCatalogGraphResult.Success)?.value
                ?: return null
            // The indexed candidate must be the exact edition that was read.
            if (detail.musicBrainzId != hit.musicBrainzId ||
                detail.isVideoOnlyContent == true
            ) return null
            editions += detail
        }
        val verified = AppleMusicBrainzEditionMatcher.match(album, editions)
            as? AppleMusicBrainzEditionMatch.Verified
            ?: return null
        val group = when (val lookup = graph.lookupReleaseGroup(
            verified.musicBrainzReleaseGroupId,
        )) {
            is MusicBrainzCatalogGraphResult.Success -> listOf(lookup.value)
            is MusicBrainzCatalogGraphResult.Failure -> emptyList()
        }
        return AppleAlbumEditionEvidence(editions, group)
    }
}

/**
 * Android album-detail evidence bridge. Rebuild only the *original* Apple
 * collection and its exact tracks for the existing three-source reconciler.
 * No external source can insert products or replace artwork, names or tracks.
 */
internal class AppleAlbumEditionReconciliationService(
    private val source: AppleAlbumEditionEvidenceSource =
        MusicBrainzBarcodeEditionEvidenceSource(),
) {
    suspend fun resolve(album: ItunesEditorialRelease): AppleTriSourceEditorialEntry? {
        if (album.appleCollectionId <= 0L ||
            album.artistId == null || album.artistId <= 0L ||
            album.appleScopedId != "itunes:${album.storefront}:album:${album.appleCollectionId}" ||
            !Regex("^[a-z]{2}$").matches(album.storefront) ||
            album.artistName.isBlank() || album.title.isBlank() ||
            album.upc?.matches(Regex("^[0-9]{8,14}$")) != true ||
            album.trackCount == null || album.trackCount <= 0 ||
            album.tracks.size != album.trackCount
        ) return null
        if (album.tracks.any { track ->
                track.type != ItunesCatalogItemType.TRACK ||
                    track.collectionId != album.appleCollectionId ||
                    track.storefront != album.storefront ||
                    track.id <= 0L || track.discNumber == null ||
                    track.discNumber <= 0 || track.trackNumber == null ||
                    track.trackNumber <= 0
            } || album.tracks.map { it.scopedId }.distinct().size != album.tracks.size ||
            album.tracks.map { it.discNumber to it.trackNumber }.distinct().size !=
            album.tracks.size
        ) return null

        val data = try {
            source.discover(album)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val rawAlbum = ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = album.appleCollectionId,
            storefront = album.storefront, title = album.title,
            artistName = album.artistName, artistId = album.artistId,
            collectionId = album.appleCollectionId, storeUrl = null,
            releaseDate = album.releaseDate, genre = album.genre,
            durationMs = null, trackNumber = null, discNumber = null,
            artworkUrl = album.artworkUrl, upc = album.upc,
            trackCount = album.trackCount,
        )
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = album.storefront,
            appleRows = listOf(rawAlbum) + album.tracks,
            mbEditions = data.editions,
            mbGroups = data.groups,
        ).singleOrNull() ?: return null
        if (result.match !is AppleMusicBrainzEditionMatch.Verified ||
            result.release.appleScopedId != album.appleScopedId ||
            result.release.title != album.title ||
            result.release.artworkUrl != album.artworkUrl ||
            result.release.tracks != album.tracks
        ) return null
        return result
    }
}
