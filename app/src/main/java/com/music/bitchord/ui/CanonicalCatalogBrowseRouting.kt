package com.music.bitchord.ui

import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import java.util.Locale

/** Typed MusicBrainz browse IDs cannot fall through into retired YouTube browse. */
private val NATIVE_CATALOG_MBY_UUID =
    Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

internal fun canonicalRowFromBrowseTarget(
    browseId: String,
    title: String,
    subtitle: String,
    artworkUri: String?,
): CanonicalCatalogSearchRow? {
    val parts = browseId.split(':')
    if (parts.size != 3 || parts[0] != "catalog" ||
        !NATIVE_CATALOG_MBY_UUID.matches(parts[2]) || title.isBlank()) return null
    val kind = when (parts[1]) {
        "ARTIST" -> CanonicalSearchKind.ARTIST
        "RELEASE", "RELEASE_GROUP" -> if (parts[1] == "RELEASE")
            CanonicalSearchKind.RELEASE else CanonicalSearchKind.RELEASE_GROUP
        else -> return null
    }
    // Never guess album / EP / single classification from a fallback label.
    return CanonicalCatalogSearchRow(
        musicBrainzId = parts[2].lowercase(Locale.ROOT),
        kind = kind,
        title = title,
        subtitle = subtitle,
        artworkUri = artworkUri,
    )
}

/** MusicBrainz public metadata is not a playable provider MediaAsset. */
internal fun legacyBrowsePlaybackAllowed(browseId: String?): Boolean =
    browseId?.startsWith("catalog:") != true
