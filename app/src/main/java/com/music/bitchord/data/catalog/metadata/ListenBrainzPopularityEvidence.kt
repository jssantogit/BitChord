package com.music.bitchord.data.catalog.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Public ListenBrainz popularity is an observation, not personal listening
 * history, MusicBrainz identity proof, or a playable MediaAsset.
 *
 * The upstream batch response explicitly returns null counts for unknown MBIDs.
 * Unknown must never be rendered or ranked as a verified zero.
 */
internal sealed interface ListenBrainzPopularityEvidence {
    data class Known(
        val recordingMbid: String,
        val listens: Long,
        val listeners: Long,
    ) : ListenBrainzPopularityEvidence

    data class Unknown(val recordingMbid: String) : ListenBrainzPopularityEvidence
}

internal sealed interface ListenBrainzPopularityParseResult {
    data class Success(val observations: List<ListenBrainzPopularityEvidence>) :
        ListenBrainzPopularityParseResult
    data object Invalid : ListenBrainzPopularityParseResult
}

@Serializable
private data class ListenBrainzRecordingPopularityRow(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("total_listen_count") val listens: Long? = null,
    @SerialName("total_user_count") val listeners: Long? = null,
)

private val listenBrainzMbidPattern =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Parse a POST /1/popularity/recording response against the exact requested
 * MusicBrainz recording IDs. Reject unsolicited IDs and duplicate rows rather
 * than accidentally trusting a provider payload for a different recording.
 */
internal fun parseListenBrainzRecordingPopularity(
    response: String,
    requestedRecordingMbids: List<String>,
): ListenBrainzPopularityParseResult {
    if (response.length > 128_000 || requestedRecordingMbids.isEmpty() || requestedRecordingMbids.size > 100) {
        return ListenBrainzPopularityParseResult.Invalid
    }
    val requested = requestedRecordingMbids.map { it.lowercase(Locale.ROOT) }
    if (requested.any { !listenBrainzMbidPattern.matches(it) } ||
        requested.distinct().size != requested.size
    ) return ListenBrainzPopularityParseResult.Invalid

    val rows = try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<List<ListenBrainzRecordingPopularityRow>>(response)
    } catch (_: Exception) {
        return ListenBrainzPopularityParseResult.Invalid
    }
    if (rows.size != requested.size) return ListenBrainzPopularityParseResult.Invalid

    val byMbid = mutableMapOf<String, ListenBrainzPopularityEvidence>()
    for (row in rows) {
        val id = row.recordingMbid?.lowercase(Locale.ROOT)
            ?: return ListenBrainzPopularityParseResult.Invalid
        if (id !in requested || id in byMbid) return ListenBrainzPopularityParseResult.Invalid
        val observation = when {
            row.listens == null && row.listeners == null ->
                ListenBrainzPopularityEvidence.Unknown(id)
            row.listens == null || row.listeners == null ||
                row.listens < 0 || row.listeners < 0 ||
                row.listeners > row.listens ->
                return ListenBrainzPopularityParseResult.Invalid
            else -> ListenBrainzPopularityEvidence.Known(id, row.listens, row.listeners)
        }
        byMbid[id] = observation
    }
    return ListenBrainzPopularityParseResult.Success(requested.map { byMbid.getValue(it) })
}

/** Community ranking is never a playback entitlement or a personal chart. */
internal fun publicListenBrainzRecordingRanking(
    evidence: List<ListenBrainzPopularityEvidence>,
): List<ListenBrainzPopularityEvidence.Known> =
    evidence.filterIsInstance<ListenBrainzPopularityEvidence.Known>()
        .sortedWith(compareByDescending<ListenBrainzPopularityEvidence.Known> { it.listeners }
            .thenByDescending { it.listens }
            .thenBy { it.recordingMbid })
