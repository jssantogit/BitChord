package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Public MusicBrainz discovery returns identities and metadata, NEVER playable
 * assets. A result must be reconciled with a provider-owned MediaAsset before
 * PLAY_NOW or ADD_TO_QUEUE can be offered.
 */
internal data class MusicBrainzCatalogPage<out T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
)

internal sealed interface MusicBrainzCatalogSearchResult<out T> {
    data class Success<T>(val page: MusicBrainzCatalogPage<T>) : MusicBrainzCatalogSearchResult<T>
    data class Failure(val reason: MusicBrainzCatalogSearchFailure) : MusicBrainzCatalogSearchResult<Nothing>
}

internal enum class MusicBrainzCatalogSearchFailure {
    INVALID_QUERY,
    INVALID_PAGE,
    UNAVAILABLE,
    MALFORMED_RESPONSE,
}

internal data class MusicBrainzArtistHit(
    val musicBrainzId: String,
    val name: String,
    val disambiguation: String?,
    val country: String?,
)

internal data class MusicBrainzReleaseGroupHit(
    val musicBrainzId: String,
    val title: String,
    val primaryType: String?,
    val firstReleaseDate: String?,
    val artistDisplay: String?,
)

internal data class MusicBrainzReleaseHit(
    val musicBrainzId: String,
    val title: String,
    val country: String?,
    val releaseDate: String?,
    val artistDisplay: String?,
)

internal data class MusicBrainzRecordingHit(
    val musicBrainzId: String,
    val title: String,
    val artistDisplay: String?,
    val durationMs: Long?,
)

/**
 * Read-only search endpoint for the independent catalog.
 *
 * Deliberately does not return SearchResult.Track/Song, because their legacy
 * videoId-based play path could silently resolve a metadata hit via YouTube.
 * The shared MusicBrainzClient rate limiter protects lookups and search alike.
 */
internal class MusicBrainzCatalogSearch(
    private val client: MusicBrainzClient = MusicBrainzClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchArtists(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzArtistHit> =
        search("artist", query, limit, offset) { raw ->
            val result = json.decodeFromString<ArtistSearchEnvelope>(raw)
            result.pageOrNull {
                result.artists?.mapNotNull { artist ->
                    val mbid = artist.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val name = artist.name.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzArtistHit(mbid, name, artist.disambiguation.nonEmpty(), artist.country.nonEmpty())
                }
            }
        }

    suspend fun searchReleaseGroups(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> =
        search("release-group", query, limit, offset) { raw ->
            val result = json.decodeFromString<ReleaseGroupSearchEnvelope>(raw)
            result.pageOrNull {
                result.releaseGroups?.mapNotNull { group ->
                    val mbid = group.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = group.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzReleaseGroupHit(
                        mbid, title, group.primaryType.nonEmpty(),
                        group.firstReleaseDate.nonEmpty(), group.artistCredit.displayArtistCredit(),
                    )
                }
            }
        }

    suspend fun searchReleases(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> =
        search("release", query, limit, offset) { raw ->
            val result = json.decodeFromString<ReleaseSearchEnvelope>(raw)
            result.pageOrNull {
                result.releases?.mapNotNull { release ->
                    val mbid = release.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = release.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzReleaseHit(
                        mbid, title, release.country.nonEmpty(),
                        release.date.nonEmpty(), release.artistCredit.displayArtistCredit(),
                    )
                }
            }
        }

    suspend fun searchRecordings(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> =
        search("recording", query, limit, offset) { raw ->
            val result = json.decodeFromString<RecordingSearchEnvelope>(raw)
            result.pageOrNull {
                result.recordings?.mapNotNull { recording ->
                    val mbid = recording.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = recording.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzRecordingHit(
                        mbid, title, recording.artistCredit.displayArtistCredit(),
                        recording.length?.takeIf { it >= 0 },
                    )
                }
            }
        }

    private suspend fun <T> search(
        path: String,
        rawQuery: String,
        limit: Int,
        offset: Int,
        decode: (String) -> MusicBrainzCatalogPage<T>?,
    ): MusicBrainzCatalogSearchResult<T> {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ")
        if (query.isBlank() || query.length > MAX_QUERY_LENGTH ||
            query.any { it.isISOControl() }
        ) return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        if (limit !in 1..100 || offset < 0) {
            return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_PAGE)
        }

        val raw = try {
            client.get(path, mapOf(
                "fmt" to "json",
                "query" to query,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
        }
        val parsed = try {
            decode(raw)
        } catch (_: Exception) {
            null
        }
        return if (parsed == null) {
            MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
        } else {
            MusicBrainzCatalogSearchResult.Success(parsed)
        }
    }

    private fun String?.nonEmpty(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String?.validSearchMbidOrNull(): String? =
        nonEmpty()?.takeIf(MBID_PATTERN::matches)?.lowercase(java.util.Locale.ROOT)

    private fun List<SearchArtistCredit>?.displayArtistCredit(): String? =
        this?.joinToString("") { credit ->
            (credit.name.nonEmpty() ?: credit.artist?.name.nonEmpty().orEmpty()) + credit.joinPhrase.orEmpty()
        }?.trim()?.takeIf(String::isNotEmpty)

    private fun <T> SearchEnvelope.pageOrNull(items: () -> List<T>?): MusicBrainzCatalogPage<T>? {
        val total = count?.takeIf { it >= 0 } ?: return null
        val start = offset?.takeIf { it >= 0 } ?: return null
        val rows = items() ?: return null
        return MusicBrainzCatalogPage(rows.distinctBy { it }, total, start)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
        val MBID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
    }
}

@Serializable
private open class SearchEnvelope(
    val count: Int? = null,
    val offset: Int? = null,
)

@Serializable
private data class SearchArtistReference(
    val name: String? = null,
)

@Serializable
private data class SearchArtistCredit(
    val name: String? = null,
    @SerialName("joinphrase") val joinPhrase: String? = null,
    val artist: SearchArtistReference? = null,
)

@Serializable
private data class SearchArtistEntry(
    val id: String? = null,
    val name: String? = null,
    val disambiguation: String? = null,
    val country: String? = null,
)

@Serializable
private data class SearchReleaseGroupEntry(
    val id: String? = null,
    val title: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
)

@Serializable
private data class SearchReleaseEntry(
    val id: String? = null,
    val title: String? = null,
    val date: String? = null,
    val country: String? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
)

@Serializable
private data class SearchRecordingEntry(
    val id: String? = null,
    val title: String? = null,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
)

@Serializable
private class ArtistSearchEnvelope(
    count: Int? = null,
    offset: Int? = null,
    val artists: List<SearchArtistEntry>? = null,
) : SearchEnvelope(count, offset)

@Serializable
private class ReleaseGroupSearchEnvelope(
    count: Int? = null,
    offset: Int? = null,
    @SerialName("release-groups") val releaseGroups: List<SearchReleaseGroupEntry>? = null,
) : SearchEnvelope(count, offset)

@Serializable
private class ReleaseSearchEnvelope(
    count: Int? = null,
    offset: Int? = null,
    val releases: List<SearchReleaseEntry>? = null,
) : SearchEnvelope(count, offset)

@Serializable
private class RecordingSearchEnvelope(
    count: Int? = null,
    offset: Int? = null,
    val recordings: List<SearchRecordingEntry>? = null,
) : SearchEnvelope(count, offset)
