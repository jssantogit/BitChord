package com.music.bitchord.data.catalog.metadata

import java.net.URI
import java.util.Locale

/**
 * Exact, promotion-eligible Apple artwork found by UPC, never by fuzzy title.
 * Keep the Apple product link beside any use of its promotional cover.
 *
 * This object is NOT a MusicBrainz artwork cache entry, a playback asset or a
 * transferable cover for an arbitrary ReleaseGroup/Recording.
 */
internal data class AppleVerifiedEditionArtwork(
    val musicBrainzReleaseId: String,
    val musicBrainzReleaseGroupId: String,
    val appleScopedId: String,
    val artworkUrl: String,
    val storeUrl: String,
)

/** Deny forged URLs even when a test or an alternate transport passes raw rows. */
private fun safeAppleStorePromotionUrl(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    return try {
        val url = URI(raw)
        url.scheme == "https" && url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            url.host?.lowercase(Locale.ROOT) in
                setOf("itunes.apple.com", "music.apple.com") &&
            !url.rawPath.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

/**
 * Called ONLY with response to public iTunes lookup?upc=<exact MB barcode>
 * &entity=song&country=<storefront>. The UPC response itself is a source
 * association; the strict Apple↔MB matcher is the independent identity proof.
 *
 * A successful lookup may omit the explicit album UPC, which does not negate
 * the barcode used to request it. Conversely, a different explicit UPC vetoes
 * the match. More than one collection, a partial tracklist, or conflict fails.
 */
internal fun appleVerifiedEditionArtwork(
    mb: MusicBrainzCatalogReleaseDetail,
    upcLookup: ItunesCatalogResult,
    storefront: String,
): AppleVerifiedEditionArtwork? {
    if (!Regex("^[a-z]{2}$").matches(storefront)) return null
    val barcode = mb.barcode?.trim()
        ?.takeIf { Regex("^[0-9]{8,14}$").matches(it) } ?: return null
    val result = upcLookup as? ItunesCatalogResult.Success ?: return null
    if (result.possiblyTruncated) return null
    val albums = result.items.filter { it.type == ItunesCatalogItemType.ALBUM }
    val tracks = result.items.filter { it.type == ItunesCatalogItemType.TRACK }
    if (albums.size != 1 || tracks.isEmpty() ||
        result.items.size != 1 + tracks.size
    ) return null
    val album = albums.single()
    if (album.storefront != storefront || album.id <= 0L ||
        album.artistId == null || album.artistId <= 0L ||
        album.trackCount == null || album.trackCount != tracks.size ||
        album.title.isBlank() || album.artistName.isNullOrBlank() ||
        !safeAppleStorePromotionUrl(album.storeUrl) ||
        safeAppleArtwork(album.artworkUrl) == null ||
        album.upc?.let { it != barcode } == true ||
        tracks.any { it.storefront != storefront || it.collectionId != album.id }
    ) return null
    val apple = ItunesEditorialCatalog.assemble(storefront, result.items)
        .singleOrNull()?.takeIf {
            it.appleCollectionId == album.id && it.appleScopedId == album.scopedId
        } ?: return null
    // The query's exact UPC is an internal matching hint; it is not written
    // onto the original Apple product or treated as sufficient on its own.
    val verified = AppleMusicBrainzEditionMatcher.match(
        apple.copy(upc = barcode), listOf(mb),
    ) as? AppleMusicBrainzEditionMatch.Verified ?: return null
    if (!verified.musicBrainzReleaseId.equals(mb.musicBrainzId, true) ||
        !verified.musicBrainzReleaseGroupId.equals(mb.groupMbid, true)
    ) return null
    return AppleVerifiedEditionArtwork(
        musicBrainzReleaseId = verified.musicBrainzReleaseId,
        musicBrainzReleaseGroupId = verified.musicBrainzReleaseGroupId,
        appleScopedId = verified.appleScopedId,
        artworkUrl = album.artworkUrl!!,
        storeUrl = album.storeUrl!!,
    )
}

/**
 * Explicit Release AND ReleaseGroup identity required, not just an artist,
 * album title, UPC or Recording relationship. Group-only callers must choose
 * a verified exact edition, not borrow the first matching title.
 */
/**
 * Apple-published, unmodified store badges. They must be placed immediately
 * beside the promotional cover and link directly to its exact store product.
 * Do not redraw the brand logo or synthesize store links from an MBID.
 */
internal fun appleApprovedBadgeForStoreUrl(storeUrl: String?): String? {
    if (!safeAppleStorePromotionUrl(storeUrl)) return null
    return when (URI(storeUrl).host.lowercase(Locale.ROOT)) {
        "music.apple.com" ->
            "https://www.apple.com/ca/itunes/link/images/link-badge-music.png"
        "itunes.apple.com" ->
            "https://www.apple.com/ca/itunes/link/images/link-badge-itunes.png"
        else -> null
    }
}

internal fun canonicalAppleArtworkFor(
    proof: AppleVerifiedEditionArtwork?,
    requestedReleaseMbid: String,
    requestedGroupMbid: String,
): String? = proof?.artworkUrl?.takeIf {
    proof.musicBrainzReleaseId.equals(requestedReleaseMbid, ignoreCase = true) &&
        proof.musicBrainzReleaseGroupId.equals(requestedGroupMbid, ignoreCase = true) &&
        safeAppleStorePromotionUrl(proof.storeUrl)
}
