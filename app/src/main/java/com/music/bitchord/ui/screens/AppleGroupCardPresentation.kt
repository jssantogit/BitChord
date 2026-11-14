package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl
import com.music.bitchord.data.catalog.metadata.safeAppleArtwork
import com.music.bitchord.data.model.HomeShelf

/** Per-card attribution is bound to the *currently rendered* artwork. */
internal data class AppleGroupCardBadge(
    val artworkUrl: String,
    val storeUrl: String,
    val badgeUrl: String,
)

/** A Group card never inherits another Group's promotional product URL. */
internal fun appleGroupCardBadgeFor(
    browseId: String?,
    displayedArtworkUrl: String?,
    verifiedPromotions: Map<String, Pair<String, String>>,
): AppleGroupCardBadge? {
    val prefix = "catalog:RELEASE_GROUP:"
    val id = browseId?.takeIf { it.startsWith(prefix) } ?: return null
    if (displayedArtworkUrl.isNullOrBlank()) return null
    val group = id.removePrefix(prefix)
    val (art, store) = verifiedPromotions[group] ?: return null
    if (displayedArtworkUrl != art || safeAppleArtwork(art) == null) return null
    val badge = appleApprovedBadgeForStoreUrl(store) ?: return null
    return AppleGroupCardBadge(art, store, badge)
}


/** A Show All overlay holds the old shelf value; always paint live card updates. */
internal fun nativeLiveArtistShelf(
    opened: HomeShelf,
    currentSections: List<HomeShelf>,
): HomeShelf = currentSections.firstOrNull { it.title == opened.title } ?: opened
