package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.ProviderCapability

/**
 * Auditable allowlist of Apple Music Web operations implemented by this
 * provider. HTTP access is not authorized by arbitrary caller-selected
 * capabilities: the route must belong to the capability's declared contract.
 *
 * Catalog search cannot be used as an alternate path to discography,
 * editorial Browse, private library, audio or user-specific resources.
 */
internal object AppleMusicWebOperationRegistry {
    private val market = Regex("[a-z]{2}")
    private val id = Regex("[0-9]{1,12}")
    private val artistViews = AppleMusicWebArtistShelf.entries
        .map { it.endpoint }
        .filter { it.startsWith("view/") }
        .map { it.removePrefix("view/") }
        .toSet()

    fun allows(capability: ProviderCapability, path: String): Boolean {
        if (path.length > 1024 || !path.startsWith("/") || path.contains("//") ||
            path.contains("..") || path.any(Char::isISOControl) ||
            '?' in path || '#' in path || '%' in path
        ) return false
        val parts = path.split('/').drop(1)
        if (parts.size < 4 || parts[0] != "v1" ||
            parts[1] != "catalog" || !market.matches(parts[2])
        ) return false
        return when (capability) {
            ProviderCapability.CATALOG_SEARCH ->
                parts.size == 4 && parts[3] == "search"
            ProviderCapability.EDITORIAL_DISCOGRAPHY ->
                when (parts[3]) {
                    "artists" -> {
                        if (parts.size < 5 || !id.matches(parts[4])) false
                        else when (parts.size) {
                            5 -> true
                            6 -> parts[5] == "albums"
                            7 -> parts[5] == "view" && parts[6] in artistViews
                            else -> false
                        }
                    }
                    "albums" -> {
                        if (parts.size < 5 || !id.matches(parts[4])) false
                        else when (parts.size) {
                            5 -> true
                            6 -> parts[5] == "tracks"
                            7 -> parts[5] == "relationships" && parts[6] == "tracks"
                            else -> false
                        }
                    }
                    else -> false
                }
            else -> false
        }
    }
}
