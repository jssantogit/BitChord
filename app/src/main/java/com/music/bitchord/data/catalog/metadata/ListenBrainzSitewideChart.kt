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
}

internal data class ListenBrainzSitewideRecording(
    val recordingMbid: String,
    val listenCount: Long,
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
        if (listens > 0L) accepted += ListenBrainzSitewideRecording(id, listens)
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
