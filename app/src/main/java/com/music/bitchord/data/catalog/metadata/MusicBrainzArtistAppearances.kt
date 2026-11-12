package com.music.bitchord.data.catalog.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    /** Exact Official MusicBrainz Release, not a guessed group sleeve. */
    val releaseMbid: String? = null,
    /** A guest credit belongs to this exact Recording, never the whole group. */
    val recordingMbid: String? = null,
    val trackTitle: String? = null,
    /** Official edition plus independent barcode/label/ISRC provenance. */
    val commerciallyVerified: Boolean = false,
)

private val ARTIST_APPEARANCE_MBID =
    Regex("""^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$""")

// "Official" in MusicBrainz is an edition status, not editorial approval of a
// third-party rework. The guest shelf intentionally favors precision over recall.
private val NON_EDITORIAL_GUEST_TITLE = Regex(
    """(?i)\b(?:remix|rework|bootleg|edit|fan[- ]?made|mashup|nightcore|karaoke|jersey|sped\s+up|slowed|flip|remake)\b|\(\s*dj\s+\S+"""
)

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
        val creditedTracks = (release["media"] as? JsonArray).orEmpty()
            .flatMap { medium ->
                val tracks = (medium as? JsonObject)?.get("tracks") as? JsonArray
                    ?: return@flatMap emptyList()
                tracks.mapNotNull track@{ value ->
                    val entry = value as? JsonObject ?: return@track null
                    val recording = entry["recording"] as? JsonObject
                    val recordingId = recording?.appearanceText("id")
                        ?.lowercase(Locale.ROOT)?.takeIf(ARTIST_APPEARANCE_MBID::matches)
                    // The edition's Track credit is the authoritative
                    // printed credit for this appearance. A Recording can be
                    // reused by several releases with different Track credits:
                    // a guest mentioned ONLY on the Recording must not turn
                    // a host-only edition Track into a false "Incluído em".
                    //
                    // Fall back to the Recording credit only when the browse
                    // response OMITS the Track credit entirely, and only for
                    // the exact proven Recording MBID. An explicitly supplied
                    // empty/malformed Track credit is not permission to borrow
                    // another entity's evidence.
                    val credits = if ("artist-credit" in entry) {
                        entry["artist-credit"] as? JsonArray ?: return@track null
                    } else {
                        if (recordingId == null) return@track null
                        recording?.get("artist-credit") as? JsonArray
                            ?: return@track null
                    }
                    val hasArtist = credits.any { part ->
                        val id = ((part as? JsonObject)?.get("artist")
                            as? JsonObject)?.appearanceText("id")
                        id?.equals(target, ignoreCase = true) == true
                    }
                    if (!hasArtist) return@track null
                    val trackTitle = recording?.appearanceText("title")
                        ?: entry.appearanceText("title")
                    val isrc = (recording?.get("isrcs") as? JsonArray).orEmpty().any {
                        val code = (it as? JsonPrimitive)?.contentOrNull
                        code != null && Regex("""[A-Za-z]{2}[A-Za-z0-9]{3}\d{7}""")
                            .matches(code)
                    }
                    Triple(recordingId, trackTitle, isrc)
                }
            }
        if (creditedTracks.isEmpty()) return@mapNotNull null
        // An album with the selected artist as primary belongs in discography.
        // Anonymous Various Artists compilations also cannot establish an
        // independently curated guest appearance without label-level evidence.
        if (artists.isEmpty() || artists.any { it.first == target } ||
            artists.any { it.second.equals("Various Artists", ignoreCase = true) })
            return@mapNotNull null
        val mainArtist = artists.mapNotNull { it.second }.firstOrNull()
            ?: return@mapNotNull null
        val group = release["release-group"] as? JsonObject ?: return@mapNotNull null
        val groupId = group.appearanceText("id")?.lowercase(Locale.ROOT)
            ?: return@mapNotNull null
        if (!ARTIST_APPEARANCE_MBID.matches(groupId)) return@mapNotNull null
        // A different edition-level credit does not prove a different
        // ReleaseGroup artist. When structured group credits are explicitly
        // supplied, ALL credited artist objects must have valid MBIDs before
        // classifying this as someone else's release. Malformed or empty
        // credits are inconclusive, never positive guest evidence.
        //
        // Omitted group credits are not a negative identity assertion. Keep
        // the previous track-scoped behavior in that case; MusicBrainz
        // browse responses may contain partial nested entities.
        group["artist-credit"]?.let { supplied ->
            val parts = supplied as? JsonArray ?: return@mapNotNull null
            var verifiedArtists = 0
            for (part in parts) {
                if (part is JsonPrimitive && part.isString) continue
                val credited = (part as? JsonObject)?.get("artist") as? JsonObject
                    ?: return@mapNotNull null
                val creditedId = credited.appearanceText("id")
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf(ARTIST_APPEARANCE_MBID::matches)
                    ?: return@mapNotNull null
                verifiedArtists++
                if (creditedId == target) return@mapNotNull null
            }
            if (verifiedArtists == 0) return@mapNotNull null
        }
        val title = group.appearanceText("title")?.takeIf { it.length <= 180 }
            ?: return@mapNotNull null
        if (!canonicalStreamingTitleIsSafe(title) ||
            NON_EDITORIAL_GUEST_TITLE.containsMatchIn(title)
        ) return@mapNotNull null
        // A MusicBrainz "Official" edition is not necessarily an official
        // artist release: community-created remix/mixtape compilations can
        // carry that status. Only an actual credited guest appearance on a
        // conventional release belongs in this editorial shelf.
        val secondaryTypes = (group["secondary-types"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT) }
            .orEmpty()
        if (secondaryTypes.any { it in setOf(
                "remix", "dj-mix", "mixtape/street", "compilation", "live", "demo",
                "interview", "spokenword",
            ) }) return@mapNotNull null
        val kind = group.appearanceText("primary-type")
            ?.takeIf { it.lowercase(Locale.ROOT) in setOf("album", "single", "ep") }
            ?: return@mapNotNull null
        val date = group.appearanceText("first-release-date")
            ?.takeIf { Regex("""\d{4}(-\d{2}){0,2}""").matches(it) }
        val editionId = release.appearanceText("id")?.lowercase(Locale.ROOT)
            ?.takeIf(ARTIST_APPEARANCE_MBID::matches)
        // "Official" alone is editable MusicBrainz data. A conventional
        // commercial edition generally carries a barcode, a named label or a
        // recording ISRC; exclude self-published mashup records lacking them
        // from the editorial guest shelf (2AT-style false positives).
        val barcode = release.appearanceText("barcode")
        val commercialBarcode = barcode != null &&
            Regex("""\d{8,14}""").matches(barcode) && barcode.any { it != '0' }
        val commercialLabel = (release["label-info"] as? JsonArray).orEmpty().any { info ->
            val label = (info as? JsonObject)?.get("label") as? JsonObject
            val labelId = label?.appearanceText("id")?.lowercase(Locale.ROOT)
            val labelName = label?.appearanceText("name")
            labelId != null && ARTIST_APPEARANCE_MBID.matches(labelId) &&
                !labelName.isNullOrBlank() &&
                labelName.lowercase(Locale.ROOT) !in setOf("[no label]", "no label", "none")
        }
        creditedTracks.map { (recordingId, trackTitle, hasIsrc) ->
            MusicBrainzArtistAppearance(
                groupId, title, mainArtist, date, kind, editionId,
                recordingMbid = recordingId,
                trackTitle = trackTitle,
                commerciallyVerified = commercialBarcode || commercialLabel || hasIsrc,
            )
        }
    }.flatMap { it.asSequence() }
        // Retain the most convincing Official edition of an exact Recording.
        .sortedWith(compareByDescending<MusicBrainzArtistAppearance> {
            it.commerciallyVerified
        }.thenByDescending { it.releaseDate.orEmpty() })
        .distinctBy { it.recordingMbid ?: it.releaseGroupMbid }
        .take(limit.coerceIn(0, 20))
        .toList()
}

internal class MusicBrainzArtistAppearancesClient(
    private val client: MusicBrainzClient = MusicBrainzClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetch: (suspend (String) -> String)? = null,
    /** Injectable offset-aware fetch for paging a crowded artist graph. */
    private val pagedFetch: (suspend (String, Int) -> String)? = null,
) {
    private data class Cached(val atMs: Long, val appearances: List<MusicBrainzArtistAppearance>,
        val healthy: Boolean)
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val cache = linkedMapOf<String, Cached>()

    /**
     * One bounded official browse page, shared rate limit and per-artist
     * in-process cache. LazyColumn may dispose/recreate the shelf on scrolling:
     * neither a positive match nor a transient offline failure should trigger
     * another MusicBrainz request on every reappearance.
     */
    suspend fun browse(artistMbid: String): List<MusicBrainzArtistAppearance> {
        val id = artistMbid.lowercase(Locale.ROOT)
        if (!ARTIST_APPEARANCE_MBID.matches(id)) return emptyList()
        mutex.lock()
        try {
            val now = nowMillis()
            val prior = cache[id]
            if (prior != null && now >= prior.atMs &&
                now - prior.atMs < when {
                    !prior.healthy -> 90_000L
                    prior.appearances.isEmpty() -> 10L * 60L * 1_000L
                    else -> 6L * 60L * 60L * 1_000L
                }
            ) return prior.appearances
            // One page can be filled entirely by alternate editions of a
            // popular act. Browse up to three pages, only when a page is
            // complete and no verified guest release has been found yet.
            // Do not make title-based guesses or sweep the whole catalog.
            val foundByRecording = linkedMapOf<String, MusicBrainzArtistAppearance>()
            var offset = 0
            var healthy = true
            for (page in 0 until 3) {
                val response = try {
                    when {
                        pagedFetch != null -> pagedFetch.invoke(id, offset)
                        fetch != null -> fetch.invoke(id)
                        else -> client.get("release", mapOf(
                            "track_artist" to id,
                            "inc" to "release-groups+artist-credits+recordings+labels+isrcs",
                            "limit" to "25",
                            "offset" to offset.toString(),
                            "fmt" to "json",
                        ))
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    healthy = false
                    break
                }
                if (response.length > 500_000) {
                    healthy = false
                    break
                }
                val root = runCatching {
                    Json.parseToJsonElement(response) as? JsonObject
                }.getOrNull()
                val list = root?.get("releases") as? JsonArray
                if (list == null) {
                    healthy = false
                    break
                }
                // Partial track-credit evidence does not prove this is a
                // genuine commercially released feature. Do not stop paging
                // because a fan edit happened to be indexed first.
                parseMusicBrainzTrackArtistAppearances(response, id)
                    .filter {
                        it.commerciallyVerified && !it.recordingMbid.isNullOrBlank() &&
                            !it.trackTitle.isNullOrBlank()
                    }
                    .forEach { appearance ->
                        foundByRecording.putIfAbsent(
                            requireNotNull(appearance.recordingMbid), appearance,
                        )
                    }
                val count = list.size
                val total = (root?.get("release-count") as? JsonPrimitive)?.intOrNull
                offset += count
                // Captures use the old simple fetch callback and represent one
                // page only. More pages require a real offset-aware transport.
                if (foundByRecording.isNotEmpty() || count == 0 ||
                    (total != null && offset >= total) ||
                    (total == null && count < 25) ||
                    (fetch != null && pagedFetch == null)
                ) break
            }
            val found = foundByRecording.values.take(12).toList()
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache[id] = Cached(nowMillis(), found, healthy = healthy)
            return found
        } finally {
            mutex.unlock()
        }
    }
}
