package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.Http
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ListeningSubmissionGate
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.catalog.metadata.ListenBrainzRequestBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class ListenBrainzSubmissionResult {
    ACCEPTED, RETRYABLE, AUTH_ERROR, REJECTED, REVOKED,
}

/**
 * Submits a stored occurrence as an import: it can have been captured hours or
 * days earlier when the phone was offline. We submit one at a time so an
 * uncertain response only risks replaying one occurrence, never a whole batch.
 */
internal fun listenBrainzImportPayload(listen: PendingListenBrainzListen): String {
    return buildJsonObject {
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
}

internal fun listenBrainzSubmissionDisposition(status: Int): ListenBrainzSubmissionResult =
    when (status) {
        in 200..299 -> ListenBrainzSubmissionResult.ACCEPTED
        401, 403 -> ListenBrainzSubmissionResult.AUTH_ERROR
        400, 413, 422 -> ListenBrainzSubmissionResult.REJECTED
        else -> ListenBrainzSubmissionResult.RETRYABLE
    }

internal suspend fun ListenBrainzManager.submitPending(
    token: String,
    listen: PendingListenBrainzListen,
): ListenBrainzSubmissionResult {
    if (token.isBlank() || !listen.isQualified() ||
        listen.accountKey != ListenBrainzOutbox.accountKey(token)
    ) return ListenBrainzSubmissionResult.REJECTED

    val bodyJson = listenBrainzImportPayload(listen)

    val guarded = ListeningSubmissionGate.execute(BuiltInProviderKeys.LISTENBRAINZ) {
        withContext(Dispatchers.IO) {
            try {
            val request = Request.Builder()
                .url("https://api.listenbrainz.org/1/submit-listens")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Token $token")
                .build()
            val (disposition, remaining, resetInSeconds) = ListenBrainzRequestBudget.limiter.run {
                awaitListenBrainzSubmissionResponse(Http.client.newCall(request))
            }
            // Never acquire the limiter's mutex again while inside run().
            ListenBrainzRequestBudget.limiter.observeRateLimit(remaining, resetInSeconds)
            disposition
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
            } catch (_: Exception) {
                ListenBrainzSubmissionResult.RETRYABLE
            }
        }
    }
    return when (guarded) {
        is ProviderCallResult.Success -> guarded.value
        is ProviderCallResult.Failed -> ListenBrainzSubmissionResult.RETRYABLE
        ProviderCallResult.Denied, ProviderCallResult.Stale ->
            ListenBrainzSubmissionResult.REVOKED
    }
}

/**
 * Worker cancellation must abort the actual OkHttp request. A blocking
 * execute() keeps a socket active after WorkManager revokes the worker.
 * Each response is closed, and rate-limit metadata is returned intact.
 */
internal suspend fun awaitListenBrainzSubmissionResponse(
    call: Call,
): Triple<ListenBrainzSubmissionResult, Long?, Long?> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(
                    Triple(ListenBrainzSubmissionResult.RETRYABLE, null, null))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    Triple(
                        listenBrainzSubmissionDisposition(it.code),
                        it.header("X-RateLimit-Remaining")?.toLongOrNull(),
                        it.header("X-RateLimit-Reset-In")?.toLongOrNull(),
                    )
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
