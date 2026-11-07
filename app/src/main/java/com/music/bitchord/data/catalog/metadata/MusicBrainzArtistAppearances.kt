package com.music.bitchord.data.catalog.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * A track-credited guest appearance on another primary artist's release.
 * MusicBrainz release?track_artist=<artist MBID> is the provenance;
 * a similarity between artist names or release titles never proves membership.
 */
internal data class MusicBrainzArtistAppearance(
    val releaseGroupMbid: String,
    val title: String,
    val artistCredit: String,
    val releaseDate: String?,
    val primaryType: String?,
)

private val ARTIST_APPEARANCE_MBID =
    Regex("""^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$""")

private fun JsonObject.appearanceText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

internal fun parseMusicBrainzTrackArtistAppearances(
    body: String,
    artistMbid: String,
    limit: Int = 12,
): List<MusicBrainzArtistAppearance> {
    val target = artistMbid.lowercase(Locale.ROOT)
    if (!ARTIST_APPEARANCE_MBID.matches(target) || body.length > 500_000) return emptyList()
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }
        .getOrNull() ?: return emptyList()
    val releases = root["releases"] as? JsonArray ?: return emptyList()
    if (releases.size > 100) return emptyList()
    return releases.asSequence().mapNotNull { element ->
        val release = element as? JsonObject ?: return@mapNotNull null
        if (!release.appearanceText("status").equals("Official", ignoreCase = true))
            return@mapNotNull null
        val credit = release["artist-credit"] as? JsonArray ?: return@mapNotNull null
        val artists = credit.mapNotNull { component ->
            val item = component as? JsonObject ?: return@mapNotNull null
            val artist = item["artist"] as? JsonObject ?: return@mapNotNull null
            val id = artist.appearanceText("id")?.lowercase(Locale.ROOT)
                ?: return@mapNotNull null
            if (!ARTIST_APPEARANCE_MBID.matches(id)) return@mapNotNull null
            id to (item.appearanceText("name") ?: artist.appearanceText("name"))
        }
        // A track_artist browse may include tribute, cover, remix or
        // miscellaneous credit links. Verify the exact MBID in a real
        // release medium's TRACK artist-credit before displaying a guest
        // appearance. An album title mentioning the selected artist is
        // never sufficient.
        val hasTargetTrack = (release["media"] as? JsonArray)
            ?.any { medium ->
                val tracks = (medium as? JsonObject)?.get("tracks") as? JsonArray
                tracks?.any { track ->
                    val trackCredit = (track as? JsonObject)?.get("artist-credit")
                        as? JsonArray
                    trackCredit?.any { part ->
                        val credited = ((part as? JsonObject)?.get("artist")
                            as? JsonObject)?.appearanceText("id")
                        credited?.equals(target, ignoreCase = true) == true
                    } == true
                } == true
            } == true
        if (!hasTargetTrack) return@mapNotNull null
        // An album with the selected artist as primary belongs in discography.
        if (artists.isEmpty() || artists.any { it.first == target })
            return@mapNotNull null
        val mainArtist = artists.mapNotNull { it.second }.firstOrNull()
            ?: return@mapNotNull null
        val group = release["release-group"] as? JsonObject ?: return@mapNotNull null
        val groupId = group.appearanceText("id")?.lowercase(Locale.ROOT)
            ?: return@mapNotNull null
        if (!ARTIST_APPEARANCE_MBID.matches(groupId)) return@mapNotNull null
        val title = group.appearanceText("title")?.takeIf { it.length <= 180 }
            ?: return@mapNotNull null
        if (!canonicalStreamingTitleIsSafe(title)) return@mapNotNull null
        val kind = group.appearanceText("primary-type")
            ?.takeIf { it.lowercase(Locale.ROOT) in setOf("album", "single", "ep") }
            ?: return@mapNotNull null
        val date = group.appearanceText("first-release-date")
            ?.takeIf { Regex("""\d{4}(-\d{2}){0,2}""").matches(it) }
        MusicBrainzArtistAppearance(groupId, title, mainArtist, date, kind)
    }.distinctBy { it.releaseGroupMbid }
        .take(limit.coerceIn(0, 20))
        .toList()
}

internal class MusicBrainzArtistAppearancesClient(
    private val client: MusicBrainzClient = MusicBrainzClient(),
) {
    /** One bounded official browse page. Shared MusicBrainz rate limit applies. */
    suspend fun browse(artistMbid: String): List<MusicBrainzArtistAppearance> {
        val id = artistMbid.lowercase(Locale.ROOT)
        if (!ARTIST_APPEARANCE_MBID.matches(id)) return emptyList()
        val response = client.get("release", mapOf(
            "track_artist" to id,
            "inc" to "release-groups+artist-credits+recordings",
            "limit" to "25",
            "offset" to "0",
            "fmt" to "json",
        ))
        return parseMusicBrainzTrackArtistAppearances(response, id)
    }
}
