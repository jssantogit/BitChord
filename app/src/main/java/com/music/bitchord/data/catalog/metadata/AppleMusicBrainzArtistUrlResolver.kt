package com.music.bitchord.data.catalog.metadata

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** MB's artist↔Apple Music URL relationship, not a name/UPC/ISRC guess. */
internal fun interface MusicBrainzArtistUrlLookup {
    suspend fun lookup(url: String): String
}

/**
 * A precise Apple artist URL is already present in the public iTunes Search row.
 * URL lookup checks MusicBrainz's explicit "apple music" artist relationship.
 * Missing/ambiguous links are not verified artist identities.
 */
internal class MusicBrainzAppleArtistUrlResolver(
    private val source: MusicBrainzArtistUrlLookup = MusicBrainzArtistUrlLookup { url ->
        MusicBrainzClient().get(
            "url", mapOf("fmt" to "json", "inc" to "artist-rels", "resource" to url),
        )
    },
) {
    suspend fun resolve(artistId: Long, storefront: String, appleArtistUrl: String?): String? {
        if (artistId <= 0L || appleArtistUrl == null ||
            !AppleArtistUrlIdentity.sameArtist(appleArtistUrl, artistId, storefront)
        ) return null
        // Store links often include Apple tracking parameters (?uo=4).
        // MusicBrainz relations may reference the same official artist path
        // without them. Probe both exact official URL forms, never by name.
        val candidates = AppleArtistUrlIdentity.lookupForms(appleArtistUrl)
        val linkedArtists = mutableSetOf<String>()
        candidates.forEach { candidate ->
            val response = try {
                source.lookup(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (response != null) {
                linkedArtists += AppleArtistUrlIdentity.linkedMusicBrainzArtists(
                    response, artistId, storefront,
                )
                if (linkedArtists.size > 1) return null
            }
        }
        return linkedArtists.singleOrNull()
    }
}

private object AppleArtistUrlIdentity {
    private const val APPLE_MUSIC_RELATION = "64785d6c-2eeb-4f86-9418-b6c2d6c53c13"
    private val mbid = Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    private val storefrontPattern = Regex("^[a-z]{2}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun sameArtist(url: String, artistId: Long, storefront: String): Boolean {
        if (!storefrontPattern.matches(storefront) || artistId <= 0L ||
            url.length > 600 || url.any(Char::isISOControl)
        ) return false
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme != "https" || uri.host !in setOf("music.apple.com", "itunes.apple.com") ||
            uri.port != -1 || uri.rawUserInfo != null || uri.fragment != null
        ) return false
        val segments = uri.path?.split('/')?.filter(String::isNotBlank).orEmpty()
        val prefix = if (segments.firstOrNull() == storefront) 1 else 0
        if (prefix == 0 && segments.firstOrNull() != "artist") return false
        if (segments.getOrNull(prefix) != "artist" || segments.size !in
            (prefix + 2)..(prefix + 3)
        ) return false
        val numeric = segments.last().removePrefix("id")
        return numeric.isNotBlank() && numeric.all(Char::isDigit) &&
            numeric.toLongOrNull() == artistId
    }

    fun lookupForms(url: String): List<String> {
        val uri = URI(url)
        val withoutTracking = "https://${uri.host}${uri.rawPath}"
        return listOf(withoutTracking, url).distinct()
    }

    fun linkedMusicBrainzArtists(
        response: String, artistId: Long, storefront: String,
    ): Set<String> =
        try {
            val root = json.parseToJsonElement(response) as? JsonObject
                ?: return emptySet()
            val resource = (root["resource"] as? JsonPrimitive)?.contentOrNull
                ?: return emptySet()
            if (!sameArtist(resource, artistId, storefront)) return emptySet()
            val urlMbid = (root["id"] as? JsonPrimitive)?.contentOrNull
                ?.lowercase(Locale.ROOT) ?: return emptySet()
            if (!mbid.matches(urlMbid)) return emptySet()
            val relations = root["relations"] as? JsonArray ?: return emptySet()
            val artists = relations.mapNotNull { relation ->
                val row = relation as? JsonObject ?: return@mapNotNull null
                if ((row["target-type"] as? JsonPrimitive)?.contentOrNull != "artist" ||
                    (row["type-id"] as? JsonPrimitive)?.contentOrNull != APPLE_MUSIC_RELATION
                ) return@mapNotNull null
                val id = ((row["artist"] as? JsonObject)?.get("id") as? JsonPrimitive)
                    ?.contentOrNull?.lowercase(Locale.ROOT)
                id?.takeIf(mbid::matches)
            }.distinct()
            artists.toSet()
        } catch (_: Exception) {
            emptySet()
        }
}

/**
 * Only the already-open Apple artist's verified external URL can authorize an
 * MBID for artist-scoped MusicBrainz and cached Discogs title discovery.
 * Without proof, fall back to ordinary MB title *suggestions*, not Discogs.
 * The pager invokes this source lazily after the Apple query reaches its cap.
 */
internal class VerifiedAppleArtistTitleHintSource(
    private val artistId: Long,
    private val storefront: String,
    private val appleArtistUrl: String?,
    private val resolver: MusicBrainzAppleArtistUrlResolver =
        MusicBrainzAppleArtistUrlResolver(),
    private val delegate: AppleArtistTitleHintSource =
        MusicBrainzDiscogsAppleTitleHintSource(),
) : AppleArtistTitleHintSource {
    override suspend fun suggest(
        artistName: String, verifiedMbArtistId: String?,
    ): List<String> {
        val linked = resolver.resolve(artistId, storefront, appleArtistUrl)
        val verified = linked?.takeIf { verifiedMbArtistId == null || it == verifiedMbArtistId }
        return delegate.suggest(artistName, verified)
    }
}
