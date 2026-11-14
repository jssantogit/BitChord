package com.music.bitchord.data.catalog.metadata

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Only a URL relationship returned by an exact MusicBrainz RELEASE lookup is
 * eligible to seed an Apple album ID. A release-group/artist/recording link or
 * a title match NEVER certifies an edition. This is a discovery hint; actual
 * cover proof still needs a complete Apple lookup and a strict tracklist join.
 */
internal fun musicBrainzAppleReleaseAlbumIds(relations: JsonElement?): List<Long> {
    val rows = relations as? JsonArray ?: return emptyList()
    return rows.mapNotNull { item ->
        val relation = item as? JsonObject ?: return@mapNotNull null
        val type = (relation["type"] as? JsonPrimitive)?.contentOrNull
            ?.lowercase(Locale.ROOT)
        if (type !in setOf("purchase for download", "streaming", "free streaming") ||
            (relation["target-type"] as? JsonPrimitive)?.contentOrNull != "url"
        ) return@mapNotNull null
        val url = relation["url"] as? JsonObject ?: return@mapNotNull null
        val raw = (url["resource"] as? JsonPrimitive)?.contentOrNull
            ?: return@mapNotNull null
        if (raw.length > 2048) return@mapNotNull null
        val parsed = try { URI(raw) } catch (_: Exception) {
            return@mapNotNull null
        }
        if (parsed.scheme != "https" ||
            parsed.host?.lowercase(Locale.ROOT) !in setOf(
                "music.apple.com", "itunes.apple.com",
            ) || parsed.userInfo != null || parsed.port != -1 ||
            parsed.rawQuery != null || parsed.rawFragment != null ||
            parsed.rawPath.isNullOrBlank() || '%' in parsed.rawPath
        ) return@mapNotNull null
        val parts = parsed.path.trim('/').split('/')
        if (parts.size !in 3..5 || !Regex("[a-z]{2}").matches(parts[0]) ||
            parts[1] != "album" || parts.drop(2).any { it.isBlank() }
        ) return@mapNotNull null
        val id = parts.last()
        if (!Regex("(?:id)?[1-9][0-9]{0,14}").matches(id))
            return@mapNotNull null
        id.removePrefix("id").toLongOrNull()
    }.distinct()
}
