package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.data.catalog.metadata.MusicBrainzClient
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Discogs Master is NOT a MusicBrainz ReleaseGroup, and Release is edition-scoped. */
internal enum class DiscogsKind { RELEASE, MASTER }

internal data class DiscogsLink(val kind: DiscogsKind, val id: Long) {
    init { require(id in 1..999_999_999_999L) }
    val attributionUrl: String
        get() = "https://www.discogs.com/${kind.name.lowercase(Locale.ROOT)}/$id"
}

/** A web relation is parsed, never passed to an arbitrary HTTP fetcher. */
internal object DiscogsLinkParser {
    private val exactPath = Regex("^/(release|master)/([1-9][0-9]{0,11})(?:-[A-Za-z0-9_-]+)?/?$")

    fun parse(raw: String): DiscogsLink? {
        if (raw.length !in 1..512) return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null
        ) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "discogs.com" && host != "www.discogs.com") return null
        val match = exactPath.matchEntire(uri.rawPath ?: return null) ?: return null
        val kind = if (match.groupValues[1] == "release") DiscogsKind.RELEASE else DiscogsKind.MASTER
        val id = match.groupValues[2].toLongOrNull()?.takeIf { it in 1..999_999_999_999L }
            ?: return null
        return DiscogsLink(kind, id)
    }
}

internal sealed interface DiscogsRelationResult {
    data class Linked(val link: DiscogsLink) : DiscogsRelationResult
    data object NoLink : DiscogsRelationResult
    data object Ambiguous : DiscogsRelationResult
    data object InvalidCanonicalId : DiscogsRelationResult
    data object InvalidResponse : DiscogsRelationResult
    data object Unavailable : DiscogsRelationResult
}

/** Only the exact MusicBrainz "discogs" URL relationship can anchor an API lookup. */
internal class DiscogsMusicBrainzLinks(
    private val musicBrainz: MusicBrainzClient = MusicBrainzClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mbid = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

    suspend fun findRelease(releaseMbid: String): DiscogsRelationResult =
        find("release", releaseMbid, DiscogsKind.RELEASE,
            "4a78823c-1c53-4176-a5f3-58026c76f2bc")

    suspend fun findReleaseGroup(groupMbid: String): DiscogsRelationResult =
        find("release-group", groupMbid, DiscogsKind.MASTER,
            "99e550f3-5ab4-3110-b5b9-fe01d970b126")

    private suspend fun find(
        entity: String, id: String, requiredKind: DiscogsKind, relationTypeId: String,
    ): DiscogsRelationResult {
        if (!mbid.matches(id)) return DiscogsRelationResult.InvalidCanonicalId
        val raw = try {
            musicBrainz.get("$entity/$id", mapOf("fmt" to "json", "inc" to "url-rels"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DiscogsRelationResult.Unavailable
        }
        return try {
            val parsed = json.parseToJsonElement(raw) as? JsonObject
                ?: return DiscogsRelationResult.InvalidResponse
            if (!parsed.field("id").equals(id, ignoreCase = true))
                return DiscogsRelationResult.InvalidResponse
            val relations = parsed["relations"] as? JsonArray
                ?: return DiscogsRelationResult.InvalidResponse
            if (relations.size > 250) return DiscogsRelationResult.InvalidResponse
            val candidates = mutableSetOf<DiscogsLink>()
            for (element in relations) {
                val relation = element as? JsonObject ?: return DiscogsRelationResult.InvalidResponse
                if (!relation.field("type").equals("discogs", ignoreCase = true) ||
                    relation.field("type-id") != relationTypeId ||
                    (relation["ended"] as? JsonPrimitive)?.contentOrNull == "true") continue
                val resource = (relation["url"] as? JsonObject)?.field("resource")
                    ?: return DiscogsRelationResult.InvalidResponse
                val link = DiscogsLinkParser.parse(resource)
                    ?: return DiscogsRelationResult.InvalidResponse
                if (link.kind != requiredKind) return DiscogsRelationResult.InvalidResponse
                candidates.add(link)
            }
            when (candidates.size) {
                0 -> DiscogsRelationResult.NoLink
                1 -> DiscogsRelationResult.Linked(candidates.single())
                else -> DiscogsRelationResult.Ambiguous
            }
        } catch (_: Exception) {
            DiscogsRelationResult.InvalidResponse
        }
    }
}

private fun JsonObject.field(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** Only CC0-style edition fields. Deliberately no images, Marketplace or user fields. */
internal data class DiscogsRecord(
    val link: DiscogsLink,
    val title: String,
    val artistNames: List<String>,
    val country: String?,
    val year: Int?,
    val labels: List<Pair<String, String>>,
    val barcodes: List<String>,
    val formats: List<String>,
    val tracklist: List<Pair<String, String>>,
    val unofficial: Boolean,
) {
    val attributionUrl: String get() = link.attributionUrl
}

/** A Discogs Master groups versions and must not be converted to an MB Release. */
internal data class DiscogsMasterRecord(
    val link: DiscogsLink,
    val title: String,
    val artistNames: List<String>,
    val year: Int?,
    /** A Discogs release candidate, not a MusicBrainz Release ID. */
    val mainReleaseId: Long?,
    val genres: List<String>,
    val styles: List<String>,
) {
    val attributionUrl: String get() = link.attributionUrl
    fun canSupplyAudio(): Boolean = false
    fun canSupplyArtwork(): Boolean = false
}

internal object DiscogsRecordParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private const val MAX_BYTES = 131_072

    /** Safely parse group-level context; never import images, release tracks or user data. */
    fun parseMaster(expected: DiscogsLink, raw: String): DiscogsMasterRecord? {
        if (expected.kind != DiscogsKind.MASTER || raw.length > MAX_BYTES) return null
        return try {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
            if ((obj["id"] as? JsonPrimitive)?.longOrNull != expected.id) return null
            val title = obj.clean("title", 240) ?: return null
            val artists = obj.objects("artists", 32) ?: return null
            val genres = obj.stringArray("genres", 64) ?: return null
            val styles = obj.stringArray("styles", 64) ?: return null
            val release = (obj["main_release"] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..999_999_999_999L }
            DiscogsMasterRecord(
                link = expected,
                title = title,
                artistNames = artists.mapNotNull { it.clean("name", 180) },
                year = (obj["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1800..2200 },
                mainReleaseId = release,
                genres = genres,
                styles = styles,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parse(expected: DiscogsLink, raw: String): DiscogsRecord? {
        if (expected.kind != DiscogsKind.RELEASE || raw.length > MAX_BYTES) return null
        return try {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return null
            if ((obj["id"] as? JsonPrimitive)?.longOrNull != expected.id) return null
            val title = obj.clean("title", 240) ?: return null
            val artists = obj.objects("artists", 32) ?: return null
            val labels = obj.objects("labels", 64) ?: return null
            val identifiers = obj.objects("identifiers", 128) ?: return null
            val formats = obj.objects("formats", 32) ?: return null
            val tracks = obj.objects("tracklist", 256) ?: return null
            val names = artists.mapNotNull { it.clean("name", 180) }
            val catalog = labels.mapNotNull { label ->
                label.clean("name", 180)?.let { name ->
                    name to (label.clean("catno", 80) ?: "")
                }
            }
            val barcode = identifiers.filter {
                it.clean("type", 40).equals("Barcode", ignoreCase = true)
            }.mapNotNull { it.clean("value", 80) }
            val formatDescriptions = formats.flatMap { format ->
                val name = format.clean("name", 80)
                val descriptions = format.stringArray("descriptions", 24) ?: return null
                listOfNotNull(name) + descriptions
            }
            // Discogs tracklists may contain disc headings and index markers,
            // neither of which is a MusicBrainz audio Track. Comparing them as
            // tracks would reject valid editions or validate wrong track counts.
            val positions = tracks.filter { track ->
                track.clean("type_", 32)?.lowercase(Locale.ROOT) in setOf(null, "track")
            }.map { track ->
                (track.clean("position", 40) ?: "") to
                    (track.clean("title", 240) ?: return null)
            }
            DiscogsRecord(
                link = expected,
                title = title,
                artistNames = names,
                country = obj.clean("country", 80),
                year = (obj["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1800..2200 },
                labels = catalog,
                barcodes = barcode,
                formats = formatDescriptions,
                tracklist = positions,
                unofficial = formatDescriptions.any { desc ->
                    desc.equals("Unofficial Release", ignoreCase = true) ||
                        desc.equals("Partially Unofficial", ignoreCase = true)
                },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.objects(key: String, max: Int): List<JsonObject>? {
        val value = this[key] ?: return emptyList()
        val array = value as? JsonArray ?: return null
        if (array.size > max) return null
        return array.map { it as? JsonObject ?: return null }
    }

    private fun JsonObject.stringArray(key: String, max: Int): List<String>? {
        val value = this[key] ?: return emptyList()
        val array = value as? JsonArray ?: return null
        if (array.size > max) return null
        return array.map { item ->
            val text = (item as? JsonPrimitive)?.contentOrNull ?: return null
            text.takeIf { it.length <= 80 && it.none(Char::isISOControl) } ?: return null
        }
    }

    private fun JsonObject.clean(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= max && it.none(Char::isISOControl) }
}

/** Current API observations expire: they never mutate MusicBrainz/Room identity facts. */
internal data class DiscogsObservation(
    val canonicalMbid: String,
    val kind: DiscogsKind,
    val record: DiscogsRecord,
    val fetchedAtMillis: Long,
) {
    companion object {
        const val TTL_MILLIS = 5 * 60 * 60 * 1000L
    }

    fun isDisplayableAt(nowMillis: Long): Boolean =
        kind == DiscogsKind.RELEASE &&
            record.link.kind == kind &&
            nowMillis >= fetchedAtMillis &&
            nowMillis - fetchedAtMillis < TTL_MILLIS

    fun canSupplyAudio(): Boolean = false
    fun canSupplyArtwork(): Boolean = false
}
