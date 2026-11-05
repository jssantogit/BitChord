package com.music.bitchord.data.catalog.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Only identity + a server-provided collaborative-filter score, never audio. */
internal data class ListenBrainzRecordingRecommendation(
    val recordingMbid: String,
    val score: Double,
)

internal sealed interface ListenBrainzRecommendationParseResult {
    data class Success(
        val items: List<ListenBrainzRecordingRecommendation>,
        val lastUpdatedSeconds: Long,
        val totalCount: Int?,
    ) : ListenBrainzRecommendationParseResult
    data object Malformed : ListenBrainzRecommendationParseResult
}

/**
 * The experimental CF endpoint gives scored MBIDs, not Songs. Returning a
 * recording candidate is not evidence of playable audio or of a release/cover.
 * The caller must join each MBID to MusicBrainz's exact Recording identity.
 */
internal fun parseListenBrainzRecommendationFeed(
    raw: String,
    expectedUser: String,
    nowSeconds: Long,
): ListenBrainzRecommendationParseResult {
    if (raw.length > 128 * 1024 || expectedUser.isBlank() || expectedUser.length > 128) {
        return ListenBrainzRecommendationParseResult.Malformed
    }
    val payload = try {
        (Json.parseToJsonElement(raw) as? JsonObject)?.get("payload") as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return ListenBrainzRecommendationParseResult.Malformed

    val responseUser = payload["user_name"]?.jsonPrimitive?.contentOrNull
        ?: return ListenBrainzRecommendationParseResult.Malformed
    if (!responseUser.equals(expectedUser, ignoreCase = true) ||
        payload["entity"]?.jsonPrimitive?.contentOrNull != "recording"
    ) return ListenBrainzRecommendationParseResult.Malformed
    val updated = payload["last_updated"]?.jsonPrimitive?.longOrNull
        ?: return ListenBrainzRecommendationParseResult.Malformed
    if (updated <= 0L || nowSeconds <= 0L || updated > nowSeconds + 300L) {
        return ListenBrainzRecommendationParseResult.Malformed
    }

    val rawItems = payload["mbids"] as? JsonArray
        ?: return ListenBrainzRecommendationParseResult.Malformed
    if (rawItems.size > 100) return ListenBrainzRecommendationParseResult.Malformed

    val validMbids = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val seen = hashSetOf<String>()
    val entries = rawItems.mapNotNull { rawItem ->
        val node = rawItem as? JsonObject ?: return@mapNotNull null
        val id = node["recording_mbid"]?.jsonPrimitive?.contentOrNull
            ?.lowercase(Locale.ROOT) ?: return@mapNotNull null
        val score = node["score"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        if (!validMbids.matches(id) || !score.isFinite() ||
            score <= 0.0 || !seen.add(id)
        ) return@mapNotNull null
        ListenBrainzRecordingRecommendation(id, score)
    }
    val total = payload["total_mbid_count"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
    return ListenBrainzRecommendationParseResult.Success(entries, updated, total)
}
