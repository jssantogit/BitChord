package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Typed graph traversal, independent of video IDs and provider-owned audio. */
internal sealed interface MusicBrainzCatalogGraphResult<out T> {
    data class Success<T>(val value: T) : MusicBrainzCatalogGraphResult<T>
    data class Failure(val reason: MusicBrainzCatalogSearchFailure) : MusicBrainzCatalogGraphResult<Nothing>
}

internal data class MusicBrainzCatalogTrack(
    val trackMbid: String,
    val recordingMbid: String,
    val title: String,
    val mediumNumber: Int,
    val trackNumber: Int,
    val durationMs: Long?,
    /** Specific track credit can differ from the release's credited artist. */
    val artistDisplay: String? = null,
    /** Shared Recording credit, parsed independently from the edition's Track credit. */
    val recordingArtistDisplay: String? = null,
    /** Nested Recording metadata is independent from release-specific Track metadata. */
    val recordingTitle: String? = null,
    val recordingDurationMs: Long? = null,
)

internal data class MusicBrainzCatalogReleaseDetail(
    val musicBrainzId: String,
    val groupMbid: String?,
    val title: String,
    val artistDisplay: String?,
    val country: String?,
    val releaseDate: String?,
    val tracks: List<MusicBrainzCatalogTrack>,
)

internal class MusicBrainzCatalogGraph(
    private val client: MusicBrainzClient = MusicBrainzClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolve genuine Recording -> Release relations when a search response
     * omitted its abbreviated release list. Never guess album art by title.
     * Results are bounded before any downstream Cover Art Archive lookups.
     */
    suspend fun lookupRecordingCoverReleases(mbid: String): List<String> {
        val normalized = mbid.validMbid() ?: return emptyList()
        val raw = try {
            client.get("recording/$normalized", mapOf("fmt" to "json", "inc" to "releases"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { return emptyList() }
        return try {
            val data = json.parseToJsonElement(raw).jsonObject
            if (data.string("id").validMbid() != normalized) return emptyList()
            data.array("releases").mapNotNull { item ->
                val release = item as? JsonObject ?: return@mapNotNull null
                val id = release.string("id").validMbid() ?: return@mapNotNull null
                Triple(id, release.string("status"), release.string("date"))
            }.distinctBy { it.first }.sortedWith(
                compareBy<Triple<String, String?, String?>> {
                    if (it.second.equals("Official", ignoreCase = true)) 0 else 1
                }.thenBy { it.third.orEmpty().ifBlank { "9999-12-31" } }
                    .thenBy { it.first },
            ).take(3).map { it.first }
        } catch (_: Exception) { emptyList() }
    }

    /** Browse, not fuzzy-name search: identity stays bound to an artist MBID. */
    suspend fun browseReleaseGroupsForArtist(
        artistMbid: String, limit: Int = 25, offset: Int = 0,
    ): MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<MusicBrainzReleaseGroupHit>> =
        paged("release-group", "artist", artistMbid, limit, offset,
            extraParameters = mapOf("release-group-status" to "website-default"),
            countKey = "release-group-count", offsetKey = "release-group-offset", arrayKey = "release-groups",
            identity = MusicBrainzReleaseGroupHit::musicBrainzId,
        ) { row ->
            MusicBrainzReleaseGroupHit(
                musicBrainzId = row.string("id").validMbid() ?: return@paged null,
                title = row.string("title")?.trim()?.takeIf(String::isNotEmpty) ?: return@paged null,
                primaryType = row.string("primary-type"),
                firstReleaseDate = row.string("first-release-date"),
                artistDisplay = row.artistDisplay(),
                secondaryTypes = (row["secondary-types"] as? JsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?.filter(String::isNotBlank)?.joinToString("|")?.ifBlank { null },
            )
        }

    /**
     * Batch up to 100 official artist editions in one MusicBrainz request,
     * including their actual ReleaseGroup MBIDs. This avoids one separate
     * rate-limited browse per album just to identify its artwork candidate.
     * The caller still checks exact titles and statuses before displaying art.
     */
    suspend fun browseOfficialReleasesForArtist(
        artistMbid: String, limit: Int = 100, offset: Int = 0,
    ): MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<MusicBrainzReleaseHit>> =
        paged("release", "artist", artistMbid, limit, offset,
            extraParameters = mapOf("status" to "official", "inc" to "release-groups"),
            countKey = "release-count", offsetKey = "release-offset", arrayKey = "releases",
            identity = MusicBrainzReleaseHit::musicBrainzId,
        ) { row ->
            MusicBrainzReleaseHit(
                musicBrainzId = row.string("id").validMbid() ?: return@paged null,
                title = row.string("title") ?: return@paged null,
                country = row.string("country"),
                releaseDate = row.string("date"),
                artistDisplay = row.artistDisplay(),
                status = row.string("status"),
                releaseGroupMbid = (row["release-group"] as? JsonObject)
                    ?.string("id").validMbid(),
                hasFrontCover = (row["cover-art-archive"] as? JsonObject)
                    ?.get("front")?.jsonPrimitive?.booleanOrNull,
            )
        }

    /** Every edition is a separate Release; multiple releases share one group. */
    suspend fun browseGroupReleases(
        groupMbid: String, limit: Int = 25, offset: Int = 0,
    ): MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<MusicBrainzReleaseHit>> =
        paged("release", "release-group", groupMbid, limit, offset,
            countKey = "release-count", offsetKey = "release-offset", arrayKey = "releases",
            identity = MusicBrainzReleaseHit::musicBrainzId,
        ) { row ->
            MusicBrainzReleaseHit(
                musicBrainzId = row.string("id").validMbid() ?: return@paged null,
                title = row.string("title")?.trim()?.takeIf(String::isNotEmpty) ?: return@paged null,
                country = row.string("country"),
                releaseDate = row.string("date"),
                artistDisplay = row.artistDisplay(),
                status = row.string("status"),
                hasFrontCover = (row["cover-art-archive"] as? JsonObject)
                    ?.get("front")?.jsonPrimitive?.booleanOrNull,
            )
        }

    /**
     * Tracks are edition-scoped MusicBrainz Track MBIDs, each pointing to a
     * separate Recording MBID. Medium and position preserve CD/vinyl order.
     */
    suspend fun lookupRelease(mbid: String): MusicBrainzCatalogGraphResult<MusicBrainzCatalogReleaseDetail> {
        val normalized = mbid.validMbid()
            ?: return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        val raw = try {
            client.get("release/$normalized", mapOf(
                "fmt" to "json", "inc" to "recordings+artist-credits+release-groups",
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
        }
        return try {
            val data = json.parseToJsonElement(raw).jsonObject
            if (data.string("id").validMbid() != normalized) {
                return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
            }
            val title = data.string("title")?.takeIf(String::isNotBlank)
                ?: return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
            val media = data.array("media")
            val parsedTracks = media.flatMapIndexed { mediumIndex, medium ->
                val mediumObj = medium as? JsonObject ?: return@flatMapIndexed emptyList()
                val number = mediumObj.integer("position")?.takeIf { it > 0 } ?: (mediumIndex + 1)
                mediumObj.array("tracks").mapIndexedNotNull { index, entry ->
                    val track = entry as? JsonObject ?: return@mapIndexedNotNull null
                    val recording = track["recording"] as? JsonObject ?: return@mapIndexedNotNull null
                    val tid = track.string("id").validMbid() ?: return@mapIndexedNotNull null
                    val rid = recording.string("id").validMbid() ?: return@mapIndexedNotNull null
                    val titleText = track.string("title")?.takeIf(String::isNotBlank)
                        ?: recording.string("title")?.takeIf(String::isNotBlank)
                        ?: return@mapIndexedNotNull null
                    MusicBrainzCatalogTrack(
                        trackMbid = tid,
                        recordingMbid = rid,
                        title = titleText,
                        mediumNumber = number,
                        trackNumber = track.integer("position")?.takeIf { it > 0 } ?: index + 1,
                        durationMs = (track.long("length") ?: recording.long("length"))?.takeIf { it >= 0 },
                        artistDisplay = track.artistDisplay() ?: recording.artistDisplay(),
                        recordingArtistDisplay = recording.artistDisplay(),
                        recordingTitle = recording.string("title"),
                        recordingDurationMs = recording.long("length")?.takeIf { it >= 0 },
                    )
                }
            }
            // An MB Track ID uniquely identifies its edition-specific Recording.
            // Contradictory rows are not silently collapsed into arbitrary truth.
            if (parsedTracks.groupBy(MusicBrainzCatalogTrack::trackMbid)
                    .values.any { rows -> rows.distinct().size > 1 }) {
                return MusicBrainzCatalogGraphResult.Failure(
                    MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                )
            }
            val tracks = parsedTracks.distinctBy(MusicBrainzCatalogTrack::trackMbid)
            MusicBrainzCatalogGraphResult.Success(MusicBrainzCatalogReleaseDetail(
                musicBrainzId = normalized,
                groupMbid = (data["release-group"] as? JsonObject)?.string("id").validMbid(),
                title = title,
                artistDisplay = data.artistDisplay(),
                country = data.string("country"),
                releaseDate = data.string("date"),
                tracks = tracks,
            ))
        } catch (_: Exception) {
            MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
        }
    }

    private suspend fun <T> paged(
        path: String, relation: String, mbid: String, limit: Int, offset: Int,
        extraParameters: Map<String, String> = emptyMap(),
        countKey: String, offsetKey: String, arrayKey: String,
        identity: (T) -> String,
        decode: (JsonObject) -> T?,
    ): MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<T>> {
        val normalized = mbid.validMbid()
            ?: return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_QUERY)
        if (limit !in 1..100 || offset < 0) {
            return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.INVALID_PAGE)
        }
        val raw = try {
            client.get(path, mapOf(
                "fmt" to "json", relation to normalized,
                "limit" to limit.toString(), "offset" to offset.toString(),
            ) + extraParameters)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.UNAVAILABLE)
        }
        return try {
            val data = json.parseToJsonElement(raw).jsonObject
            val total = (data.integer(countKey) ?: data.integer("count"))
                ?.takeIf { it >= 0 }
                ?: return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
            val actualOffset = (data.integer(offsetKey) ?: data.integer("offset"))
                ?.takeIf { it >= 0 }
                ?: return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
            val items = data[arrayKey] as? JsonArray
                ?: return MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
            MusicBrainzCatalogGraphResult.Success(
                MusicBrainzCatalogPage(items.mapNotNull { (it as? JsonObject)?.let(decode) }.distinctBy(identity),
                    total, actualOffset, consumedCount = items.size),
            )
        } catch (_: Exception) {
            MusicBrainzCatalogGraphResult.Failure(MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE)
        }
    }

    private fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()
    private fun JsonObject.integer(key: String): Int? =
        runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()
    private fun JsonObject.long(key: String): Long? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()

    private fun JsonObject.artistDisplay(): String? =
        array("artist-credit").joinToString("") { item ->
            val credit = item as? JsonObject ?: return@joinToString ""
            (credit.string("name")
                ?: (credit["artist"] as? JsonObject)?.string("name")).orEmpty() +
                // MusicBrainz joinphrase spaces carry the intended typography
                // (e.g. " feat. "). Unlike artist names, do not trim them.
                runCatching { credit["joinphrase"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull().orEmpty()
        }.trim().takeIf(String::isNotEmpty)

    private fun String?.validMbid(): String? =
        this?.trim()?.lowercase(Locale.ROOT)?.takeIf(MBID::matches)

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
