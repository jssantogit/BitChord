package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Read-only Discogs database search. It requires a USER-provided token;
 * no fallback to search scraping or to client-embedded keys is allowed.
 * Search thumbnails/titles are hints ONLY, never edition identity evidence.
 */
internal data class DiscogsSearchRequest(
    val artist: String,
    val title: String? = null,
    val barcode: String? = null,
    val catalogNumber: String? = null,
    val year: Int? = null,
    val page: Int = 1,
    val limit: Int = 12,
) {
    fun isValid(): Boolean {
        fun safe(value: String?) = value == null || (
            value.length <= 120 && value.isNotBlank() &&
                value.none { Character.isISOControl(it) }
        )
        return safe(artist) && artist.isNotBlank() && safe(title) &&
            safe(barcode) && safe(catalogNumber) &&
            page in 1..3 && limit in 1..20 &&
            (year == null || year in 1800..2200)
    }
}

internal sealed interface DiscogsSearchResult {
    data class Found(val ids: List<Long>, val nextPage: Int? = null) : DiscogsSearchResult
    data object Empty : DiscogsSearchResult
    data object AuthRequired : DiscogsSearchResult
    data object Disabled : DiscogsSearchResult
    data object InvalidQuery : DiscogsSearchResult
    data object Malformed : DiscogsSearchResult
    data object Unavailable : DiscogsSearchResult
    data class Throttled(val retryAfterSeconds: Long?) : DiscogsSearchResult
}

internal interface DiscogsSearchTransport {
    suspend fun search(
        query: DiscogsSearchRequest,
        token: String,
        userAgent: String,
    ): DiscogsHttpResponse
}

/**
 * Separate transport from the anonymous Release API so a credential is never
 * sent to a non-search endpoint, a redirect target or an untrusted host.
 */
internal class KtorDiscogsSearchTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000L
            connectTimeoutMillis = 7_000L
            socketTimeoutMillis = 12_000L
        }
    },
) : DiscogsSearchTransport {
    override suspend fun search(
        query: DiscogsSearchRequest, token: String, userAgent: String,
    ): DiscogsHttpResponse {
        require(query.isValid())
        val response = client.get("https://api.discogs.com/database/search") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/vnd.discogs.v2.discogs+json")
            header(HttpHeaders.Authorization, "Discogs token=$token")
            parameter("type", "release")
            parameter("artist", query.artist.trim())
            query.title?.let { parameter("release_title", it.trim()) }
            query.barcode?.let { parameter("barcode", it.trim()) }
            query.catalogNumber?.let { parameter("catno", it.trim()) }
            query.year?.let { parameter("year", it) }
            parameter("per_page", query.limit)
            parameter("page", query.page)
        }
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > MAX_RESPONSE_BYTES) {
            response.bodyAsChannel().cancel()
            return DiscogsHttpResponse(413, "")
        }
        val output = java.io.ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size().toLong() + count > MAX_RESPONSE_BYTES) {
                channel.cancel()
                return DiscogsHttpResponse(413, "")
            }
            output.write(buffer, 0, count)
        }
        val retry = response.headers[HttpHeaders.RetryAfter]
            ?.toLongOrNull()?.takeIf { it in 0..86_400L }
        return DiscogsHttpResponse(response.status.value,
            output.toByteArray().toString(Charsets.UTF_8), retry)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 131_072L
    }
}

internal class DiscogsDatabaseSearchClient(
    private val transport: DiscogsSearchTransport = KtorDiscogsSearchTransport(),
    private val tokenProvider: () -> String? = { null },
    private val enabled: Boolean = false,
    private val limiter: DiscogsRateLimiter = DiscogsRequestBudget.limiter,
    private val userAgent: String =
        "BitChord/${BuildConfig.VERSION_NAME} (https://github.com/jssantogit/BitChord)",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: DiscogsSearchRequest): DiscogsSearchResult {
        if (!enabled) return DiscogsSearchResult.Disabled
        if (!query.isValid()) return DiscogsSearchResult.InvalidQuery
        // Authentication is opt-in and fail-closed, including if Keystore is
        // unavailable. Never log or embed credentials in diagnostics/results.
        val token = tokenProvider()?.trim()?.takeIf(::isValidDiscogsSearchToken)
            ?: return DiscogsSearchResult.AuthRequired
        val response = try {
            limiter.runHttpLimited { transport.search(query, token, userAgent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DiscogsSearchResult.Unavailable
        }
        return when (response.status) {
            200 -> parseResult(response.body, query.page)
            401, 403 -> DiscogsSearchResult.AuthRequired
            404 -> DiscogsSearchResult.Empty
            429 -> DiscogsSearchResult.Throttled(response.retryAfterSeconds)
            else -> DiscogsSearchResult.Unavailable
        }
    }

    private fun parseResult(raw: String, requestedPage: Int): DiscogsSearchResult {
        if (raw.length > 131_072) return DiscogsSearchResult.Malformed
        return try {
            val obj = json.parseToJsonElement(raw) as? JsonObject
                ?: return DiscogsSearchResult.Malformed
            val results = obj["results"] as? JsonArray
                ?: return DiscogsSearchResult.Malformed
            if (results.size > 100) return DiscogsSearchResult.Malformed
            val ids = results.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                if ((row["type"] as? JsonPrimitive)?.contentOrNull != "release") {
                    return@mapNotNull null
                }
                (row["id"] as? JsonPrimitive)?.longOrNull
                    ?.takeIf { it in 1..999_999_999_999L }
            }.distinct().take(20)
            val paging = obj["pagination"] as? JsonObject
            val page = (paging?.get("page") as? JsonPrimitive)?.longOrNull
            val pages = (paging?.get("pages") as? JsonPrimitive)?.longOrNull
            val nextPage = if (page == requestedPage.toLong() &&
                pages != null && pages in 1..10_000 &&
                page < pages && page in 1..2
            ) (page + 1).toInt() else null
            if (ids.isEmpty()) DiscogsSearchResult.Empty
            else DiscogsSearchResult.Found(ids, nextPage)
        } catch (_: Exception) {
            DiscogsSearchResult.Malformed
        }
    }
}

internal sealed interface DiscogsAuthenticatedMatch {
    data class Verified(val record: DiscogsRecord) : DiscogsAuthenticatedMatch
    data class Ambiguous(val count: Int) : DiscogsAuthenticatedMatch
    data class Candidates(val count: Int) : DiscogsAuthenticatedMatch
    data object NoMatch : DiscogsAuthenticatedMatch
    data object AuthRequired : DiscogsAuthenticatedMatch
    data object Unavailable : DiscogsAuthenticatedMatch
}

/**
 * Discover IDs by Discogs search; hydrate Release details through the already
 * budgeted public DiscogsClient before making identity or editorial claims.
 */
internal class DiscogsAuthenticatedDiscovery(
    private val search: DiscogsDatabaseSearchClient,
    private val editions: DiscogsClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun findForEdition(canonical: CanonicalEditionEvidence): DiscogsAuthenticatedMatch {
        val artist = canonical.creditedArtist?.takeIf(String::isNotBlank)
            ?: return DiscogsAuthenticatedMatch.NoMatch
        val barcode = canonical.barcodes.firstOrNull()
        // Search endpoints are independent indexes. Never accept the first
        // verified result without checking for another verified edition in
        // the second index: that would make a barcode/title collision silent.
        val queries = buildList {
            if (barcode != null) add(DiscogsSearchRequest(artist = artist, barcode = barcode))
            add(DiscogsSearchRequest(artist = artist, title = canonical.title))
        }
        val ids = LinkedHashSet<Long>()
        for (query in queries) {
            when (val result = search.search(query)) {
                is DiscogsSearchResult.Found -> {
                    // This lookup sees only page 1; unexamined pages may
                    // contain other equally strong Release candidates.
                    // Fail closed rather than claiming an exact edition.
                    if (result.nextPage != null) {
                        return DiscogsAuthenticatedMatch.Ambiguous(result.ids.size)
                    }
                    ids.addAll(result.ids)
                }
                DiscogsSearchResult.Empty -> Unit
                DiscogsSearchResult.AuthRequired -> return DiscogsAuthenticatedMatch.AuthRequired
                // An incomplete candidate search cannot establish uniqueness.
                else -> return DiscogsAuthenticatedMatch.Unavailable
            }
        }
        if (ids.isEmpty()) return DiscogsAuthenticatedMatch.NoMatch
        // The hydration budget is deliberately 8 Release details. If either
        // index returned more than that, we cannot prove uniqueness.
        if (ids.size > 8) return DiscogsAuthenticatedMatch.Ambiguous(ids.size)
        return when (val match = DiscogsUnlinkedMatch.select(canonical, hydrate(ids.toList()))) {
            is DiscogsUnlinkedMatch.Verified ->
                DiscogsAuthenticatedMatch.Verified(match.record)
            is DiscogsUnlinkedMatch.Ambiguous ->
                DiscogsAuthenticatedMatch.Ambiguous(match.records.size)
            is DiscogsUnlinkedMatch.Candidates ->
                DiscogsAuthenticatedMatch.Candidates(match.records.size)
            DiscogsUnlinkedMatch.NoMatch -> DiscogsAuthenticatedMatch.NoMatch
        }
    }

    /**
     * For a known artist with a completed MusicBrainz catalog check, return
     * source-only metadata entries. The caller must compare existing groups
     * before declaring a release truly missing from MusicBrainz.
     */
    suspend fun findForArtist(
        artistName: String,
        mbCatalogChecked: Boolean,
        knownDiscogsReleaseIds: Set<Long> = emptySet(),
        knownMusicBrainzTitles: Set<String> = emptySet(),
    ): List<DiscogsShadowRelease> {
        if (!mbCatalogChecked) return emptyList()
        val first = search.search(DiscogsSearchRequest(artist = artistName))
            as? DiscogsSearchResult.Found ?: return emptyList()
        val firstRecords = hydrate(first.ids).map {
            DiscogsPublicEditionSnapshot(it, nowMillis())
        }
        val current = DiscogsShadowCatalog.select(firstRecords, artistName, true,
            nowMillis(), knownDiscogsReleaseIds, knownMusicBrainzTitles)
        if (current.isNotEmpty()) return current
        // Only spend the second-page budget when the first page is entirely
        // unusable. Artist detail loading must not launch 20+ requests.
        val next = first.nextPage?.takeIf { it == 2 } ?: return emptyList()
        val second = search.search(DiscogsSearchRequest(artist = artistName, page = next))
            as? DiscogsSearchResult.Found ?: return emptyList()
        val secondRecords = hydrate(second.ids).map {
            DiscogsPublicEditionSnapshot(it, nowMillis())
        }
        return DiscogsShadowCatalog.select(secondRecords, artistName, true,
            nowMillis(), knownDiscogsReleaseIds, knownMusicBrainzTitles)
    }

    private suspend fun hydrate(ids: List<Long>): List<DiscogsRecord> {
        val found = ArrayList<DiscogsRecord>()
        for (id in ids.distinct().take(8)) {
            when (val result = editions.fetchRelease(id)) {
                is DiscogsLookupResult.Found -> found += result.record
                is DiscogsLookupResult.Throttled,
                DiscogsLookupResult.Unauthorized,
                DiscogsLookupResult.Disabled -> break
                else -> Unit
            }
        }
        return found
    }
}
