package com.music.bitchord.data.catalog.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** Exact Release lookup only; not inferred from title or group type. */
    val isVideoOnlyContent: Boolean? = null,
    val completeness: MusicBrainzReleaseJsonCompleteness = MusicBrainzReleaseJsonCompleteness(),
    /** Exact edition source facts for Discogs cross-verification, never display authority. */
    val hasCompleteTracklist: Boolean = false,
    val status: String? = null,
    val barcode: String? = null,
    val artistCreditNames: List<String> = emptyList(),
    val editionLabels: List<String> = emptyList(),
    val editionFormats: List<String> = emptyList(),
    val editionLabelCatalogPairs: List<Pair<String, String>> = emptyList(),
    val editionLabelCatalogComplete: Boolean = true,
)

/** Transient source proof; defaults deliberately deny destructive reconciliation. */
internal data class MusicBrainzReleaseJsonCompleteness(
    val countsExplicitAndExact: Boolean = false,
    val offsetsValid: Boolean = false,
    val positionsExplicitUnique: Boolean = false,
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
                secondaryTypes = (row["secondary-types"] as? JsonArray)?.let { types ->
                    // A provided [] clears an old tag, unlike an omitted field.
                    // Malformed entries cannot silently masquerade as [].
                    if (types.any { it !is JsonPrimitive || !it.isString }) null
                    else types.map { (it as JsonPrimitive).content.trim() }
                        .filter(String::isNotBlank).joinToString("|")
                },
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
            extraParameters = mapOf("status" to "official", "inc" to "release-groups+media"),
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
                isVideoOnlyMedia = row.videoOnlyMediaEvidence(),
            )
        }

    /** Every edition is a separate Release; multiple releases share one group. */
    suspend fun browseGroupReleases(
        groupMbid: String, limit: Int = 25, offset: Int = 0,
    ): MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<MusicBrainzReleaseHit>> =
        paged("release", "release-group", groupMbid, limit, offset,
            extraParameters = mapOf("inc" to "media"),
            countKey = "release-count", offsetKey = "release-offset", arrayKey = "releases",
            identity = MusicBrainzReleaseHit::musicBrainzId,
        ) { row ->
            // Exact browse scope proves the group, but a contradictory
            // nested claim must never be silently overwritten. Count the
            // malformed row for pagination, then reject its identity.
            val claimedGroup = row["release-group"]
            if (claimedGroup != null) {
                val claimedId = (claimedGroup as? JsonObject)?.string("id")?.validMbid()
                    ?: return@paged null
                if (!claimedId.equals(groupMbid.trim(), ignoreCase = true))
                    return@paged null
            }
            MusicBrainzReleaseHit(
                musicBrainzId = row.string("id").validMbid() ?: return@paged null,
                title = row.string("title")?.trim()?.takeIf(String::isNotEmpty) ?: return@paged null,
                country = row.string("country"),
                releaseDate = row.string("date"),
                artistDisplay = row.artistDisplay(),
                status = row.string("status"),
                // This is an exact release-group=<MBID> browse. MusicBrainz
                // often omits nested release-group in this response; carry
                // the verified scope as explicit edition provenance.
                releaseGroupMbid = groupMbid.trim().lowercase(Locale.ROOT),
                hasFrontCover = (row["cover-art-archive"] as? JsonObject)
                    ?.get("front")?.jsonPrimitive?.booleanOrNull,
                isVideoOnlyMedia = row.videoOnlyMediaEvidence(),
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
                "fmt" to "json", "inc" to "recordings+artist-credits+release-groups+labels",
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
            val media = data["media"] as? JsonArray
                ?: return MusicBrainzCatalogGraphResult.Failure(
                    MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                )
            val decodedByMedium = media.mapIndexed { mediumIndex, medium ->
                val mediumObj = medium as? JsonObject
                    ?: return MusicBrainzCatalogGraphResult.Failure(
                        MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                    )
                if ("track-offset" in mediumObj && mediumObj.integer("track-offset") != 0) {
                    return MusicBrainzCatalogGraphResult.Failure(
                        MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                    )
                }
                val number = if ("position" in mediumObj) {
                    mediumObj.integer("position")?.takeIf { it >= 0 }
                        ?: return MusicBrainzCatalogGraphResult.Failure(
                            MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                        )
                } else {
                    mediumIndex + 1
                }
                val rawTracks = mediumObj["tracks"] as? JsonArray
                    ?: return MusicBrainzCatalogGraphResult.Failure(
                        MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                    )
                rawTracks.mapIndexed { index, entry ->
                    val track = entry as? JsonObject
                        ?: return MusicBrainzCatalogGraphResult.Failure(
                            MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                        )
                    val recording = track["recording"] as? JsonObject
                        ?: return MusicBrainzCatalogGraphResult.Failure(
                            MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                        )
                    val tid = track.string("id").validMbid()
                        ?: return MusicBrainzCatalogGraphResult.Failure(
                            MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                        )
                    val rid = recording.string("id").validMbid()
                        ?: return MusicBrainzCatalogGraphResult.Failure(
                            MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                        )
                    val titleText = track.string("title")?.takeIf(String::isNotBlank)
                        ?: recording.string("title")?.takeIf(String::isNotBlank)
                        ?: return MusicBrainzCatalogGraphResult.Failure(
                            MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                        )
                    MusicBrainzCatalogTrack(
                        trackMbid = tid,
                        recordingMbid = rid,
                        title = titleText,
                        mediumNumber = number,
                        trackNumber = if ("position" in track) {
                            track.integer("position")?.takeIf { it >= 0 }
                                ?: return MusicBrainzCatalogGraphResult.Failure(
                                    MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                                )
                        } else {
                            index + 1
                        },
                        durationMs = (track.long("length") ?: recording.long("length"))?.takeIf { it >= 0 },
                        artistDisplay = track.artistDisplay() ?: recording.artistDisplay(),
                        recordingArtistDisplay = recording.artistDisplay(),
                        recordingTitle = recording.string("title"),
                        recordingDurationMs = recording.long("length")?.takeIf { it >= 0 },
                    )
                }
            }
            val parsedTracks = decodedByMedium.flatten()
            // Media track-count belongs to THIS specific release and medium.
            // Truncated or undecodable Tracks cannot be published as a
            // complete edition or overwrite an existing offline tracklist.
            val declaredCountIncomplete = media.indices.any { index ->
                val medium = media[index] as? JsonObject ?: return@any false
                if ("track-count" !in medium) return@any false
                val expected = medium.integer("track-count") ?: return@any true
                val rawTracks = medium["tracks"] as? JsonArray ?: return@any true
                expected < 0 || rawTracks.size != expected ||
                    decodedByMedium[index].size != expected ||
                    decodedByMedium[index].map { it.trackMbid }.distinct().size != expected
            }
            val jsonCompleteness = MusicBrainzReleaseJsonCompleteness(
                countsExplicitAndExact = media.isNotEmpty() && media.all { entry ->
                    val medium = entry as? JsonObject ?: return@all false
                    val count = medium.integer("track-count") ?: return@all false
                    val rawTracks = medium["tracks"] as? JsonArray ?: return@all false
                    count >= 0 && count == rawTracks.size &&
                        rawTracks.mapNotNull { (it as? JsonObject)?.string("id").validMbid() }
                            .distinct().size == count
                },
                offsetsValid = media.all { entry ->
                    val medium = entry as? JsonObject ?: return@all false
                    ("track-offset" !in medium || medium.integer("track-offset") == 0) &&
                        medium.integer("position")?.let { it >= 0 } == true
                },
                positionsExplicitUnique = media.all { entry ->
                    val medium = entry as? JsonObject ?: return@all false
                    val tracks = medium["tracks"] as? JsonArray ?: return@all false
                    tracks.all { (it as? JsonObject)?.integer("position")?.let { n -> n >= 0 } == true } &&
                        tracks.mapNotNull { (it as? JsonObject)?.integer("position") }.distinct().size == tracks.size
                } && media.mapNotNull { (it as? JsonObject)?.integer("position") }.distinct().size == media.size,
            )
            if (declaredCountIncomplete ||
                parsedTracks.map { it.trackMbid }.distinct().size != parsedTracks.size) {
                return MusicBrainzCatalogGraphResult.Failure(
                    MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                )
            }
            // An MB Track ID uniquely identifies its edition-specific Recording.
            // Contradictory rows are not silently collapsed into arbitrary truth.
            if (parsedTracks.groupBy(MusicBrainzCatalogTrack::trackMbid)
                    .values.any { rows -> rows.distinct().size > 1 }) {
                return MusicBrainzCatalogGraphResult.Failure(
                    MusicBrainzCatalogSearchFailure.MALFORMED_RESPONSE,
                )
            }
            val tracks = parsedTracks.sortedWith(
                compareBy<MusicBrainzCatalogTrack> { it.mediumNumber }
                    .thenBy { it.trackNumber }
                    .thenBy { it.trackMbid },
            )
            val mediaEvidence = data.videoOnlyMediaEvidence()
            val recordingEvidence = data.videoOnlyRecordingEvidence()
            MusicBrainzCatalogGraphResult.Success(MusicBrainzCatalogReleaseDetail(
                musicBrainzId = normalized,
                groupMbid = (data["release-group"] as? JsonObject)?.string("id").validMbid(),
                title = title,
                artistDisplay = data.artistDisplay(),
                country = data.string("country"),
                releaseDate = data.string("date"),
                tracks = tracks,
                // The stricter #209 parser's source-completeness gate is the
                // authority here. Discogs may not verify a partial Track graph.
                hasCompleteTracklist = tracks.isNotEmpty() &&
                    // For read-only edition corroboration, a complete, offset-zero
                    // source track array is ordered even when individual track
                    // position fields are omitted; #209's stricter completeness
                    // proof remains separately available for destructive uses.
                    jsonCompleteness.countsExplicitAndExact &&
                    jsonCompleteness.offsetsValid &&
                    (data.integer("media-count") == null ||
                        data.integer("media-count") == media.size),
                status = data.string("status"),
                barcode = data.string("barcode"),
                artistCreditNames = data.artistCreditNames(),
                editionLabels = data.array("label-info").take(12).mapNotNull { entry ->
                    val info = entry as? JsonObject ?: return@mapNotNull null
                    val label = (info["label"] as? JsonObject)?.string("name")
                        ?.takeIf { it.length <= 180 } ?: return@mapNotNull null
                    val catalogNumber = info.string("catalog-number")
                        ?.takeIf { it.length <= 80 }
                    if (catalogNumber != null) "$label ($catalogNumber)" else label
                }.distinct().take(6),
                editionLabelCatalogComplete = data["label-info"]?.let { raw ->
                    raw is JsonArray && raw.size <= 64 &&
                        raw.all { it is JsonObject }
                } ?: true,
                editionLabelCatalogPairs = data.array("label-info").take(64)
                    .mapNotNull { entry ->
                        val info = entry as? JsonObject ?: return@mapNotNull null
                        val label = (info["label"] as? JsonObject)?.string("name")
                            ?.takeIf { it.length <= 180 } ?: return@mapNotNull null
                        val catalog = info.string("catalog-number")
                            ?.takeIf { it.length <= 80 } ?: return@mapNotNull null
                        label to catalog
                    }.distinct(),
                editionFormats = media.mapNotNull { entry ->
                    (entry as? JsonObject)?.string("format")
                        ?.takeIf { it.length <= 80 }
                }.distinct().take(4),
                isVideoOnlyContent = when {
                    // Conflicting positive source fields are not proof of an
                    // audio-only or video-only edition. Keep it unknown.
                    mediaEvidence != null && recordingEvidence != null &&
                        mediaEvidence != recordingEvidence -> null
                    mediaEvidence == true || recordingEvidence == true -> true
                    mediaEvidence == false || recordingEvidence == false -> false
                    else -> null
                },
                completeness = jsonCompleteness,
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

    /**
     * Official MusicBrainz releases may contain only video media. A medium
     * format is positive evidence only when it is unambiguously video-specific.
     * DVD-Audio, CD and mixed CD/DVD packages remain eligible; Digital Media
     * can ALSO contain video files, so its format alone is inconclusive.
     * Omitted / ambiguous formats (e.g. Blu-ray) are not proof of audio.
     * Uses the same source-supported 'inc=media' on the existing browse GET.
     */
    private fun JsonObject.videoOnlyMediaEvidence(): Boolean? {
        val mediums = this["media"] as? JsonArray ?: return null
        if (mediums.isEmpty()) return null
        val formats = mediums.map { (it as? JsonObject)?.string("format")
            ?.lowercase(Locale.ROOT) }
        val explicitlyVideo = setOf(
            "dvd-video", "dualdisc (dvd-video side)",
            "dvdplus (dvd-video side)", "vcd", "svcd",
            "vhs", "betamax", "laserdisc", "8\" laserdisc", "12\" laserdisc",
        )
        val explicitlyAudio = setOf(
            "cd", "dvd-audio", "vinyl", "cassette",
            "sacd", "minidisc",
            "dualdisc (cd side)", "dualdisc (dvd-audio side)",
            "dvdplus (cd side)", "dvdplus (dvd-audio side)",
        )
        return when {
            formats.all { it != null && it in explicitlyVideo } -> true
            formats.any { it != null && it in explicitlyAudio } -> false
            else -> null
        }
    }

    /**
     * Recording.video is per Recording, not per ReleaseGroup.
     * Never conclude that ALL tracks are videos if an included medium's
     * track-count exceeds the returned rows, a medium is malformed, or one
     * medium has no usable track array. Unknown video flags stay unknown.
     */
    private fun JsonObject.videoOnlyRecordingEvidence(): Boolean? {
        val media = this["media"] as? JsonArray ?: return null
        if (media.isEmpty()) return null
        val flags = mutableListOf<Boolean?>()
        for (medium in media) {
            val item = medium as? JsonObject ?: return null
            val tracks = item["tracks"] as? JsonArray ?: return null
            if (tracks.isEmpty()) return null
            val expectedCount = item.integer("track-count")
            if (expectedCount != null && expectedCount != tracks.size) return null
            for (track in tracks) {
                val itemTrack = track as? JsonObject ?: return null
                val recording = itemTrack["recording"] as? JsonObject ?: return null
                // Without both exact Track and Recording MBIDs the source
                // cannot corroborate a video flag for the edition graph.
                if (itemTrack.string("id").validMbid() == null ||
                    recording.string("id").validMbid() == null
                ) return null
                flags += runCatching {
                    recording["video"]?.jsonPrimitive?.booleanOrNull
                }.getOrNull()
            }
        }
        return when {
            flags.isEmpty() -> null
            flags.all { it == true } -> true
            flags.any { it == false } -> false
            else -> null
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

    /** Structured MB credit identities, never split a visible joined name. */
    private fun JsonObject.artistCreditNames(): List<String> {
        val credits = array("artist-credit")
        if (credits.isEmpty()) return emptyList()
        return credits.map { entry ->
            val credit = entry as? JsonObject ?: return emptyList()
            credit.string("name")
                ?: (credit["artist"] as? JsonObject)?.string("name")
                ?: return emptyList()
        }
    }

    private fun String?.validMbid(): String? =
        this?.trim()?.lowercase(Locale.ROOT)?.takeIf(MBID::matches)

    private companion object {
        val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
