package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.music.bitchord.data.Http
import com.music.bitchord.data.catalog.metadata.ListenBrainzRequestBudget
import kotlinx.serialization.json.JsonPrimitive

object ListenBrainzManager {
    private const val TAG = "ListenBrainzManager"
    private const val API_URL = "https://api.listenbrainz.org/1/submit-listens"

    suspend fun submitPlayingNow(
        token: String,
        song: Song?,
        positionMs: Long,
        durationMsOverride: Long? = null,
        primaryArtistOnly: Boolean = false,
    ): Boolean {
        if (token.isBlank() || song == null) return false
        return withContext(Dispatchers.IO) {
            try {
                val durationMs = durationMsOverride ?: parseDurationMs(song.durationText)
                // The API rejects a zero/negative duration_ms, and it is
                // optional — so only send it when it is actually known.
                val durationPart = if (durationMs > 0) "\"duration_ms\":$durationMs," else ""
                val releaseName = song.albumName.orEmpty()
                val releasePart = if (releaseName.isBlank()) "" else "\"release_name\":\"${escapeJson(releaseName)}\","
                val artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist
                val trackMetadata = """{"track_metadata":{"artist_name":"${escapeJson(artist)}","track_name":"${escapeJson(song.title)}",$releasePart"additional_info":{${durationPart}"position_ms":$positionMs,"submission_client":"BitChord"}}}"""
                val bodyJson = "{\"listen_type\":\"playing_now\",\"payload\":[$trackMetadata]}"
                // Never log listening payloads: titles, artists and timestamps are personal data.
                val body = bodyJson.toRequestBody("application/json".toMediaType())
                val request =
                    Request.Builder()
                        .url(API_URL)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $token")
                        .build()

                ListenBrainzRequestBudget.limiter.run {
                    Http.client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.d(TAG, "playing_now submitted")
                            true
                        } else {
                            Log.w(TAG, "playing_now submit failed: HTTP ${resp.code}")
                            false
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                Log.e(TAG, "submitPlayingNow failed", ex)
                false
            }
        }
    }

    suspend fun submitFinished(
        token: String,
        song: Song?,
        startMs: Long,
        endMs: Long,
        listenedMs: Long,
        durationMsOverride: Long? = null,
        primaryArtistOnly: Boolean = false,
    ): Boolean {
        // A completed HTTP request is not proof of a listen: short skips,
        // seeks to the end and time spent paused must never be submitted.
        if (token.isBlank() || song == null || startMs / 1_000L < 1_033_430_400L ||
            endMs <= startMs
        ) return false
        return withContext(Dispatchers.IO) {
            try {
                val durationMs = durationMsOverride?.takeIf { it > 0L }
                    ?: parseDurationMs(song.durationText)
                if (!listenBrainzQualifiesAsListen(listenedMs, durationMs)) {
                    return@withContext false
                }
                val durationPart = if (durationMs > 0) "\"duration_ms\":$durationMs," else ""
                val releaseName = song.albumName.orEmpty()
                val releasePart = if (releaseName.isBlank()) "" else "\"release_name\":\"${escapeJson(releaseName)}\","
                val listenedAtStart = startMs / 1_000L
                val artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist
                val trackMetadata = """{"listened_at":$listenedAtStart,"track_metadata":{"artist_name":"${escapeJson(artist)}","track_name":"${escapeJson(song.title)}",$releasePart"additional_info":{${durationPart}"start_ms":$startMs,"end_ms":$endMs,"submission_client":"BitChord"}}}"""
                val bodyJson = "{\"listen_type\":\"single\",\"payload\":[$trackMetadata]}"
                // Never log listening payloads: titles, artists and timestamps are personal data.
                val body = bodyJson.toRequestBody("application/json".toMediaType())
                val request =
                    Request.Builder()
                        .url(API_URL)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $token")
                        .build()

                ListenBrainzRequestBudget.limiter.run {
                    Http.client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Log.d(TAG, "finished listen submitted")
                            true
                        } else {
                            Log.w(TAG, "finished listen submit failed: HTTP ${resp.code}")
                            false
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                Log.e(TAG, "submitFinished failed", ex)
                false
            }
        }
    }

    private fun parseDurationMs(text: String?): Long {
        if (text == null) return 0L
        val parts = text.split(":")
        if (parts.size != 2) return 0L
        val minutes = parts[0].toLongOrNull() ?: return 0L
        val seconds = parts[1].toLongOrNull() ?: return 0L
        return (minutes * 60 + seconds) * 1000
    }

    private fun escapeJson(s: String): String = listenBrainzEscapedJsonText(s)
}

/** Quotes are inserted by the payload; preserve every JSON string escape, including CJK/control chars. */
internal fun listenBrainzEscapedJsonText(value: String): String =
    JsonPrimitive(value).toString().removeSurrounding("\"")
