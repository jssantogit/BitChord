package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException

/**
 * Reverse proof for a MusicBrainz barcode when the direct Apple album JSON
 * omitted UPC. The PUBLIC iTunes lookup?upc=...&entity=song must return
 * exactly the Apple collection already open in this storefront, plus the same
 * complete track IDs, titles and ordered positions. A UPC by itself never
 * certifies a MusicBrainz Release or any artist identity.
 *
 * This validates the *Apple-side* barcode association; the independent
 * Official MB Release / tracklist crosswalk is checked separately.
 */
internal class AppleUpcReverseLookupVerifier(
    private val itunes: ItunesCatalogClient = ItunesCatalogClient(),
) {
    suspend fun verifies(album: ItunesEditorialRelease, barcode: String): Boolean {
        if (!Regex("^[0-9]{8,14}$").matches(barcode) ||
            !Regex("^[a-z]{2}$").matches(album.storefront) ||
            album.appleCollectionId <= 0L || album.artistId == null ||
            album.artistId <= 0L || album.upc != null ||
            album.appleScopedId !=
                "itunes:${album.storefront}:album:${album.appleCollectionId}" ||
            album.title.isBlank() || album.artistName.isBlank() ||
            album.trackCount == null || album.trackCount <= 0 ||
            album.tracks.size != album.trackCount
        ) return false

        val lookup = try {
            itunes.lookupUpc(barcode, album.storefront)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val source = lookup as? ItunesCatalogResult.Success ?: return false
        // A capped or malformed Apple response cannot exclude other releases
        // sharing that code or omitted tracks; never use first-wins.
        if (source.possiblyTruncated) return false
        val collections = source.items.filter { it.type == ItunesCatalogItemType.ALBUM }
        val tracks = source.items.filter { it.type == ItunesCatalogItemType.TRACK }
        if (collections.size != 1 || tracks.size != album.trackCount ||
            source.items.size != 1 + tracks.size
        ) return false
        val exact = collections.single()
        if (exact.storefront != album.storefront ||
            exact.id != album.appleCollectionId ||
            exact.scopedId != album.appleScopedId ||
            exact.artistId != album.artistId ||
            !exact.artistName.equals(album.artistName, ignoreCase = true) ||
            exact.title != album.title ||
            exact.upc?.let { it != barcode } == true ||
            exact.trackCount != album.trackCount
        ) return false

        fun ordered(items: List<ItunesCatalogItem>): List<ItunesCatalogItem> =
            items.sortedWith(compareBy<ItunesCatalogItem> {
                it.discNumber ?: Int.MAX_VALUE
            }.thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.id })
        val appleTracks = ordered(album.tracks)
        val lookupTracks = ordered(tracks)
        if (appleTracks.map { it.scopedId }.distinct().size != appleTracks.size ||
            lookupTracks.map { it.scopedId }.distinct().size != lookupTracks.size ||
            appleTracks.map { it.discNumber to it.trackNumber }.distinct().size !=
                appleTracks.size ||
            lookupTracks.map { it.discNumber to it.trackNumber }.distinct().size !=
                lookupTracks.size
        ) return false
        return appleTracks.zip(lookupTracks).all { (opened, returned) ->
            opened.type == ItunesCatalogItemType.TRACK &&
                returned.type == ItunesCatalogItemType.TRACK &&
                opened.storefront == album.storefront &&
                returned.storefront == album.storefront &&
                opened.collectionId == album.appleCollectionId &&
                returned.collectionId == album.appleCollectionId &&
                opened.id == returned.id && opened.title == returned.title &&
                opened.discNumber != null && opened.discNumber > 0 &&
                opened.trackNumber != null && opened.trackNumber > 0 &&
                opened.discNumber == returned.discNumber &&
                opened.trackNumber == returned.trackNumber
        }
    }
}
