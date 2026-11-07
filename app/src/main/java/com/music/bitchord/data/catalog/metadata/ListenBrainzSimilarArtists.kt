package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

internal data class ListenBrainzSimilarArtist(val mbid: String, val name: String)

private val SIMILAR_ARTIST_MBID =
    Regex("""^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$""")

/** LB Radio results contain seed and related artists, plus recordings.
 * We use ONLY the similar_artist_mbid/name evidence and exclude the seed.
 * Accept both documented nested dictionary and flat response shapes.
 */
internal fun parseListenBrainzSimilarArtists(
    body: String,
    artistMbid: String,
): List<ListenBrainzSimilarArtist> {
    val seed = artistMbid.lowercase(Locale.ROOT)
    if (!SIMILAR_ARTIST_MBID.matches(seed) || body.length > 500_000) return emptyList()
    val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        ?: return emptyList()
    val rows = LinkedHashMap<String, ListenBrainzSimilarArtist>()
    var visited = 0
    fun walk(node: JsonElement, depth: Int) {
        if (depth > 6 || visited++ > 500 || rows.size >= 12) return
        when (node) {
            is JsonArray -> node.take(100).forEach { walk(it, depth + 1) }
            is JsonObject -> {
                val id = (node["similar_artist_mbid"] as? JsonPrimitive)?.contentOrNull
                    ?.lowercase(Locale.ROOT)
                val name = (node["similar_artist_name"] as? JsonPrimitive)
                    ?.contentOrNull?.trim()
                if (id != null && SIMILAR_ARTIST_MBID.matches(id) &&
                    id != seed && !name.isNullOrBlank() && name.length <= 120 &&
                    name.none(Char::isISOControl)
                ) {
                    rows.putIfAbsent(id, ListenBrainzSimilarArtist(id, name))
                } else node.values.take(100).forEach { walk(it, depth + 1) }
            }
            else -> Unit
        }
    }
    walk(parsed, 0)
    return rows.values.toList()
}

internal class ListenBrainzSimilarArtistsClient(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 8_000L
            socketTimeoutMillis = 15_000L
        }
    },
    private val limiter: ListenBrainzRateLimiter = ListenBrainzRequestBudget.limiter,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetch: (suspend (String) -> Pair<Int, String>)? = null,
) {
    private data class Entry(val at: Long, val value: List<ListenBrainzSimilarArtist>,
        val healthy: Boolean)
    private val cache = linkedMapOf<String, Entry>()
    private val lock = Mutex()

    /** Public, read-only LB radio similarities; no account or inferred names. */
    suspend fun load(seedArtistMbid: String): List<ListenBrainzSimilarArtist> {
        val id = seedArtistMbid.lowercase(Locale.ROOT)
        if (!SIMILAR_ARTIST_MBID.matches(id)) return emptyList()
        lock.lock()
        try {
        val cached = cache[id]
        val now = nowMillis()
        if (cached != null && now >= cached.at &&
            now - cached.at < if (cached.healthy)
                if (cached.value.isEmpty()) 900_000L else 21_600_000L
            else 90_000L
        ) return cached.value
        val response = try {
            limiter.run {
                if (fetch != null) fetch(id) else measureMetadataHttp(
                    provider = "listenbrainz", endpoint = "artist_similar",
                    status = { result: Pair<Int, String> -> result.first },
                ) {
                    val http = client.get("https://api.listenbrainz.org/1/lb-radio/artist/$id") {
                        parameter("mode", "easy")
                        parameter("max_similar_artists", 12)
                        parameter("max_recordings_per_artist", 1)
                        header(HttpHeaders.UserAgent, "BitChord/" + BuildConfig.VERSION_NAME)
                    }
                    http.status.value to if (http.status.value == 200) http.bodyAsText() else ""
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        val healthy = response?.first == 200
        val resolved = if (healthy)
            parseListenBrainzSimilarArtists(response?.second.orEmpty(), id)
        else emptyList()
        if (cache.size >= 64) cache.remove(cache.keys.first())
        cache[id] = Entry(nowMillis(), resolved, healthy)
        return resolved
        } finally {
            lock.unlock()
        }
    }
}
