package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import java.util.UUID

/** Player menus frequently open away from canonical search/discovery screens.
 * A known Recording → Release/ReleaseGroup route must not depend on finding
 * the same album in the currently rendered shelf before navigation can work.
 *
 * The caller only offers this action after the catalog resolver has verified
 * its edition or unique group. Validate its type and UUID again at this UI
 * boundary; never turn a recording MBID or display text into a browse route. */
internal fun openVerifiedCanonicalPlayerAlbum(
    route: String,
    albumName: String?,
    artist: String,
    fallbackAlbumTitle: String,
    onOpenDetail: (String, String, String, BrowseType) -> Unit,
): Boolean {
    val prefix = when {
        route.startsWith("catalog:RELEASE_GROUP:") -> "catalog:RELEASE_GROUP:"
        route.startsWith("catalog:RELEASE:") -> "catalog:RELEASE:"
        else -> return false
    }
    val mbid = route.removePrefix(prefix)
    // UUID.fromString accepts abbreviated components. Compare against the
    // canonical representation so only a full MusicBrainz MBID can pass.
    val parsed = runCatching { UUID.fromString(mbid) }.getOrNull()
    if (parsed == null || !parsed.toString().equals(mbid, ignoreCase = true)) return false

    val title = albumName?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackAlbumTitle
    onOpenDetail(route, title, artist, BrowseType.ALBUM)
    return true
}
