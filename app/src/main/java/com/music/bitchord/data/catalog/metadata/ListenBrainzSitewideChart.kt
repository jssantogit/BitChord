package com.music.bitchord.data.catalog.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Sitewide stats count listens in a requested time window, NOT unique listeners
 * and NOT the user's private Listening Event Store. A valid MBID here is only a
 * catalog identity candidate; it never supplies a stream or artwork rights.
 */
internal enum class ListenBrainzSitewideRange(val apiValue: String) {
    WEEK("week"),
    MONTH("month"),
    ALL_TIME("all_time"),
}

/**
 * The upstream clock is an observation about the published chart, not the
 * time this device last fetched it. An old snapshot cannot become fresh by
 * downloading the same JSON again. Keep this policy shared by cache and UI.
 */
internal fun listenBrainzSitewideSnapshotIsCurrent(
    range: ListenBrainzSitewideRange,
    lastUpdatedSeconds: Long?,
    nowMs: Long,
): Boolean {
    val upstream = lastUpdatedSeconds
        ?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }
        ?: return false
    val age = nowMs - upstream * 1_000L
    val dayMs = 24L * 60L * 60L * 1_000L
    val maxAge = when (range) {
        ListenBrainzSitewideRange.WEEK -> 14L * dayMs
        ListenBrainzSitewideRange.MONTH -> 45L * dayMs
        ListenBrainzSitewideRange.ALL_TIME -> 60L * dayMs
    }
    return age in (-dayMs)..maxAge
}

internal data class ListenBrainzSitewideRecording(
    val recordingMbid: String,
    val listenCount: Long,
    /** Credit evidence comes from ListenBrainz, not artist-name matching. */
    val artistMbids: List<String> = emptyList(),
)

internal sealed interface ListenBrainzSitewideParseResult {
    data class Success(
        val recordings: List<ListenBrainzSitewideRecording>,
        val range: String,
        val lastUpdatedSeconds: Long?,
    ) : ListenBrainzSitewideParseResult
    data object Invalid : ListenBrainzSitewideParseResult
}

@Serializable
private data class ListenBrainzSitewideEnvelope(
    val payload: ListenBrainzSitewidePayload? = null,
)

@Serializable
private data class ListenBrainzSitewidePayload(
    val range: String? = null,
    val offset: Int? = null,
    @SerialName("last_updated") val lastUpdated: Long? = null,
    val recordings: List<ListenBrainzSitewideRawRecording>? = null,
)

@Serializable
private data class ListenBrainzSitewideRawRecording(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("listen_count") val listens: Long? = null,
    @SerialName("artist_mbids") val artistMbids: List<String>? = null,
)

private val SITEWIDE_MUSICBRAINZ_RECORDING_ID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** Parse the official /1/stats/sitewide/recordings payload without name matching. */
internal fun parseListenBrainzSitewideRecordings(
    response: String,
    expectedRange: ListenBrainzSitewideRange,
): ListenBrainzSitewideParseResult {
    if (response.length > 256_000) return ListenBrainzSitewideParseResult.Invalid
    val payload = try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<ListenBrainzSitewideEnvelope>(response).payload
    } catch (_: Exception) {
        return ListenBrainzSitewideParseResult.Invalid
    } ?: return ListenBrainzSitewideParseResult.Invalid
    if (payload.range != expectedRange.apiValue || (payload.offset ?: 0) != 0 ||
        (payload.lastUpdated != null && payload.lastUpdated <= 0L)
    ) return ListenBrainzSitewideParseResult.Invalid

    val incoming = payload.recordings ?: return ListenBrainzSitewideParseResult.Invalid
    if (incoming.size > 100) return ListenBrainzSitewideParseResult.Invalid
    val seen = mutableSetOf<String>()
    val accepted = mutableListOf<ListenBrainzSitewideRecording>()
    for (item in incoming) {
        val id = item.recordingMbid?.lowercase(Locale.ROOT)
            ?.takeIf(SITEWIDE_MUSICBRAINZ_RECORDING_ID::matches)
            ?: continue // The public API explicitly permits missing MBIDs.
        if (!seen.add(id)) return ListenBrainzSitewideParseResult.Invalid
        val listens = item.listens ?: continue
        if (listens < 0L) return ListenBrainzSitewideParseResult.Invalid
        if (listens > 0L) accepted += ListenBrainzSitewideRecording(
            id, listens, item.artistMbids.orEmpty()
                .map { it.lowercase(Locale.ROOT) }
                .filter(SITEWIDE_MUSICBRAINZ_RECORDING_ID::matches)
                .distinct(),
        )
    }
    return ListenBrainzSitewideParseResult.Success(
        recordings = accepted.sortedWith(
            compareByDescending<ListenBrainzSitewideRecording> { it.listenCount }
                .thenBy { it.recordingMbid },
        ),
        range = expectedRange.apiValue,
        lastUpdatedSeconds = payload.lastUpdated,
    )
}
