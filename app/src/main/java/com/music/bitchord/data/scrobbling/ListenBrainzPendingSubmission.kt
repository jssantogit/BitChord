package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.Http
import com.music.bitchord.data.catalog.metadata.ListenBrainzRequestBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class ListenBrainzSubmissionResult {
    ACCEPTED, RETRYABLE, AUTH_ERROR, REJECTED,
}

/**
 * Submits a stored occurrence as an import: it can have been captured hours or
 * days earlier when the phone was offline. We submit one at a time so an
 * uncertain response only risks replaying one occurrence, never a whole batch.
 */
internal suspend fun ListenBrainzManager.submitPending(
    token: String,
    listen: PendingListenBrainzListen,
): ListenBrainzSubmissionResult {
    if (token.isBlank() || !listen.isQualified() ||
        listen.accountKey != ListenBrainzOutbox.accountKey(token)
    ) return ListenBrainzSubmissionResult.REJECTED

    val bodyJson = buildJsonObject {
        put("listen_type", "import")
        putJsonArray("payload") {
            addJsonObject {
                put("listened_at", listen.startMs / 1_000L)
                putJsonObject("track_metadata") {
                    put("artist_name", listen.artist)
                    put("track_name", listen.title)
                    if (listen.album.isNotBlank()) put("release_name", listen.album)
                    putJsonObject("additional_info") {
                        if (listen.durationMs > 0L && listen.durationMs <= 2_073_600_000L) {
                            put("duration_ms", listen.durationMs)
                        }
                        put("start_ms", listen.startMs)
                        put("end_ms", listen.endMs)
                        put("submission_client", "BitChord")
                    }
                }
            }
        }
    }.toString()

    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.listenbrainz.org/1/submit-listens")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Token $token")
                .build()
            ListenBrainzRequestBudget.limiter.run {
                Http.client.newCall(request).execute().use { response ->
                    ListenBrainzRequestBudget.limiter.observeRateLimit(
                        response.header("X-RateLimit-Remaining")?.toLongOrNull(),
                        response.header("X-RateLimit-Reset-In")?.toLongOrNull(),
                    )
                    when (response.code) {
                        in 200..299 -> ListenBrainzSubmissionResult.ACCEPTED
                        401, 403 -> ListenBrainzSubmissionResult.AUTH_ERROR
                        400, 413, 422 -> ListenBrainzSubmissionResult.REJECTED
                        else -> ListenBrainzSubmissionResult.RETRYABLE
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ListenBrainzSubmissionResult.RETRYABLE
        }
    }
}
