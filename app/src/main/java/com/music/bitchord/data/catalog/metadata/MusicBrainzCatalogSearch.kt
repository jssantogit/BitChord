package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Public MusicBrainz discovery returns identities and metadata, NEVER playable
 * assets. A result must be reconciled with a provider-owned MediaAsset before
 * PLAY_NOW or ADD_TO_QUEUE can be offered.
 */
internal data class MusicBrainzCatalogPage<out T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    /** How many raw server rows this page consumed, including invalid/duplicate hits. */
    val consumedCount: Int = items.size,
) {
    /**
     * Never derive a server cursor from filtered UI results: rejected MBIDs and
     * duplicate rows have already consumed their upstream offset positions.
     * Browsing releases can also return fewer rows than the requested limit.
     */
    val nextOffset: Int? get() =
        if (consumedCount <= 0 || offset < 0 || offset >= total ||
            consumedCount >= total - offset
        ) null else offset + consumedCount
}

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
    val artworkUri: String? = null,
    /** Pipe-separated official MusicBrainz secondary types; unknown remains null. */
    val secondaryTypes: String? = null,
)

internal data class MusicBrainzReleaseHit(
    val musicBrainzId: String,
    val title: String,
    val country: String?,
    val releaseDate: String?,
    val artistDisplay: String?,
    val artworkUri: String? = null,
    /** Release status is supplied by MusicBrainz, never inferred from its title. */
    val status: String? = null,
    /** Search response's exact Release -> ReleaseGroup association, not a title match. */
    val releaseGroupMbid: String? = null,
    /** Positive CAA Front availability flag attached to this *specific* Release. */
    val hasFrontCover: Boolean? = null,
)

// A MusicBrainz cover-art-archive.front flag is a presence hint only.
// Never synthesize direct /front-500 URLs from it: CAA may label the
// underlying image Poster/Track or leave its approval unverified.
// CoverArtArchive must validate typed image metadata before presentation.

internal data class MusicBrainzRecordingHit(
    val musicBrainzId: String,
    val title: String,
    val artistDisplay: String?,
    val durationMs: Long?,
    /** Art comes from an actual linked MusicBrainz Release, never title matching. */
    val coverReleaseMbid: String? = null,
    /** A matching published release is evidence, unlike an arbitrary Recording MBID. */
    // Nullable because Room's offline search projection does not select these
    // live-only evidence fields. Missing != positively verified Official.
    val hasOfficialAudioRelease: Boolean? = null,
    val isVideo: Boolean? = null,
)

/**
 * Read-only search endpoint for the independent catalog.
 *
 * Deliberately does not return SearchResult.Track/Song, because their legacy
 * videoId-based play path could silently resolve a metadata hit via YouTube.
 * The shared MusicBrainzClient rate limiter protects lookups and search alike.
 */
/**
 * Search's raw MusicBrainz corpus is retained in the index, but General's
 * streaming-oriented rows expose only recordings with an official audio
 * release. Avoid blocking legitimate label-issued remixes/instrumentals.
 * Reject video-only and user-edited titles even if mislabeled "Official".
 */
/** Explicit edition status is required for public streaming-style Release tiles. */
internal fun isCuratedMusicBrainzRelease(hit: MusicBrainzReleaseHit): Boolean =
    hit.status.equals("Official", ignoreCase = true) &&
        canonicalStreamingTitleIsSafe(hit.title)

internal fun isCuratedMusicBrainzRecording(hit: MusicBrainzRecordingHit): Boolean =
    hit.hasOfficialAudioRelease == true && hit.isVideo != true &&
        canonicalStreamingTitleIsSafe(hit.title)

internal fun canonicalStreamingTitleIsSafe(title: String): Boolean {
    if (title.isBlank()) return false
    val normalized = title.trim()
    if (NON_STREAMING_TITLE.containsMatchIn(normalized)) return false
    return !MEMBER_PRACTICE_VERSION.containsMatchIn(normalized)
}

private val NON_STREAMING_TITLE = Regex(
    """(?i)\b(?:performance\s*(?:ver(?:sion)?\.?|video)|dance\s*(?:practice|fix|performance)|practice\s*ver(?:sion)?\.?|jersey\s*flip|fan[- ]?(?:edit|made)|fancam|music\s*video|sped\s*up|slowed(?:\s*(?:and|&)\s*reverb)?|nightcore|mashup|rehearsal|fix\s*ver(?:sion)?\.?)\b"""
)
private val MEMBER_PRACTICE_VERSION = Regex(
    """\([A-Z]{3,}(?:\s*&\s*[A-Z]{3,})+\s+ver\.?\)"""
)

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
            result.pageOrNull(result.artists?.size, MusicBrainzArtistHit::musicBrainzId) {
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
        searchReleaseGroupsInternal(query, limit, offset)

    suspend fun searchReleaseGroupsForArtistMbid(
        artistMbid: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchReleaseGroupsInternal("arid:$id", limit, offset, fieldedQuery = true)
    }

    private suspend fun searchReleaseGroupsInternal(
        query: String, limit: Int, offset: Int, fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseGroupHit> =
        search("release-group", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<ReleaseGroupSearchEnvelope>(raw)
            result.pageOrNull(result.releaseGroups?.size, MusicBrainzReleaseGroupHit::musicBrainzId) {
                result.releaseGroups?.mapNotNull { group ->
                    val mbid = group.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = group.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzReleaseGroupHit(
                        mbid, title, group.primaryType.nonEmpty(),
                        group.firstReleaseDate.nonEmpty(), group.artistCredit.displayArtistCredit(),
                        secondaryTypes = group.secondaryTypes?.filter(String::isNotBlank)
                            ?.joinToString("|")?.ifBlank { null },
                    )
                }
            }
        }

    suspend fun searchReleases(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> =
        searchReleasesInternal(query, limit, offset)

    suspend fun searchReleasesForArtistMbid(
        artistMbid: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchReleasesInternal("arid:$id", limit, offset, fieldedQuery = true)
    }

    private suspend fun searchReleasesInternal(
        query: String, limit: Int, offset: Int, fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzReleaseHit> =
        search("release", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<ReleaseSearchEnvelope>(raw)
            result.pageOrNull(result.releases?.size, MusicBrainzReleaseHit::musicBrainzId) {
                result.releases?.mapNotNull { release ->
                    val mbid = release.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = release.title.nonEmpty() ?: return@mapNotNull null
                    MusicBrainzReleaseHit(
                        mbid, title, release.country.nonEmpty(),
                        release.date.nonEmpty(), release.artistCredit.displayArtistCredit(),
                        status = release.status.nonEmpty(),
                        releaseGroupMbid = release.releaseGroup?.id.validSearchMbidOrNull(),
                    )
                }
            }
        }

    suspend fun searchRecordings(
        query: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> =
        searchRecordingsInternal(query, limit, offset)

    suspend fun searchRecordingsForArtistMbid(
        artistMbid: String,
        limit: Int = 25,
        offset: Int = 0,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> {
        val id = artistMbid.validSearchMbidOrNull()
            ?: return MusicBrainzCatalogSearchResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        return searchRecordingsInternal("arid:$id AND status:official", limit, offset, fieldedQuery = true)
    }

    private suspend fun searchRecordingsInternal(
        query: String, limit: Int, offset: Int, fieldedQuery: Boolean = false,
    ): MusicBrainzCatalogSearchResult<MusicBrainzRecordingHit> =
        search("recording", query, limit, offset, fieldedQuery) { raw ->
            val result = json.decodeFromString<RecordingSearchEnvelope>(raw)
            result.pageOrNull(result.recordings?.size, MusicBrainzRecordingHit::musicBrainzId) {
                result.recordings?.mapNotNull { recording ->
                    val mbid = recording.id.validSearchMbidOrNull() ?: return@mapNotNull null
                    val title = recording.title.nonEmpty() ?: return@mapNotNull null
                    val official = recording.releases.orEmpty().filter { release ->
                        release.status.equals("Official", ignoreCase = true) &&
                            release.id.validSearchMbidOrNull() != null
                    }
                    // An official release alone does not prove that its cover
                    // depicts an audio sleeve: a MusicBrainz "Other" / video
                    // release can have a video still marked Front by CAA.
                    val audioReleases = official.filter { release ->
                        release.releaseGroup?.primaryType?.trim()?.lowercase(Locale.ROOT) in
                            setOf("album", "ep", "single")
                    }
                    val releaseForArt = if (recording.video == true) null else
                        audioReleases.sortedWith(compareBy<SearchRecordingRelease> {
                            it.date.orEmpty().ifBlank { "9999-12-31" }
                        }.thenBy { it.id.orEmpty() }).firstOrNull()?.id
                    MusicBrainzRecordingHit(
                        mbid, title, recording.artistCredit.displayArtistCredit(),
                        recording.length?.takeIf { it >= 0 },
                        coverReleaseMbid = releaseForArt,
                        // A clean recording title on an Official video/Other
                        // edition is still not positive evidence of official
                        // audio. Match the *same* supported Album/EP/Single
                        // evidence required for trustworthy cover association.
                        hasOfficialAudioRelease = audioReleases.isNotEmpty(),
                        isVideo = recording.video == true,
                    )
                }
            }
        }

    private suspend fun <T> search(
        path: String,
        rawQuery: String,
        limit: Int,
        offset: Int,
        fieldedQuery: Boolean = false,
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
                // User input is ordinary text, never unescaped Lucene syntax.
                "dismax" to if (fieldedQuery) "false" else "true",
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

    private fun <T> SearchEnvelope.pageOrNull(
        rawCount: Int?,
        identity: (T) -> String,
        items: () -> List<T>?, 
    ): MusicBrainzCatalogPage<T>? {
        val total = count?.takeIf { it >= 0 } ?: return null
        val start = offset?.takeIf { it >= 0 } ?: return null
        val consumed = rawCount?.takeIf { it >= 0 } ?: return null
        val rows = items() ?: return null
        return MusicBrainzCatalogPage(rows.distinctBy(identity), total, start, consumed)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
        val MBID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Serializable concrete envelopes own their count/offset properties. */
private interface SearchEnvelope {
    val count: Int?
    val offset: Int?
}

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
    @SerialName("secondary-types") val secondaryTypes: List<String>? = null,
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
)

@Serializable
private data class SearchReleaseGroupReference(
    val id: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
)

@Serializable
private data class SearchReleaseEntry(
    val id: String? = null,
    val title: String? = null,
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
    @SerialName("release-group") val releaseGroup: SearchReleaseGroupReference? = null,
)

@Serializable
private data class SearchRecordingRelease(
    val id: String? = null,
    val date: String? = null,
    val status: String? = null,
    @SerialName("release-group") val releaseGroup: SearchReleaseGroupReference? = null,
)

@Serializable
private data class SearchRecordingEntry(
    val id: String? = null,
    val title: String? = null,
    val length: Long? = null,
    val video: Boolean? = null,
    @SerialName("artist-credit") val artistCredit: List<SearchArtistCredit>? = null,
    val releases: List<SearchRecordingRelease>? = null,
)

@Serializable
private class ArtistSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    val artists: List<SearchArtistEntry>? = null,
) : SearchEnvelope

@Serializable
private class ReleaseGroupSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    @SerialName("release-groups") val releaseGroups: List<SearchReleaseGroupEntry>? = null,
) : SearchEnvelope

@Serializable
private class ReleaseSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    val releases: List<SearchReleaseEntry>? = null,
) : SearchEnvelope

@Serializable
private class RecordingSearchEnvelope(
    override val count: Int? = null,
    override val offset: Int? = null,
    val recordings: List<SearchRecordingEntry>? = null,
) : SearchEnvelope
