package com.music.bitchord.data.catalog.provider.opensubsonic

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.Http
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A private POST seam: standard authentication remains strictly inside Catalog. */
internal interface OpenSubsonicDecisionTransport {
    suspend fun post(endpoint: String, parameters: Map<String, String>, body: String): String
}

/**
 * Redirects are deliberately disabled: query credentials may never be forwarded
 * to a new origin. Cancellation interrupts the underlying network call.
 */
internal class OkHttpOpenSubsonicDecisionTransport(
    private val client: OkHttpClient = Http.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) : OpenSubsonicDecisionTransport {
    override suspend fun post(endpoint: String, parameters: Map<String, String>, body: String): String =
        suspendCancellableCoroutine { continuation ->
            val base = endpoint.toHttpUrlOrNull()
            if (base == null) {
                continuation.resumeWithException(IOException("Invalid provider decision endpoint"))
                return@suspendCancellableCoroutine
            }
            val target = base.newBuilder().apply {
                parameters.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
            val request = Request.Builder()
                .url(target)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("OpenSubsonic decision transport unavailable"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw OpenSubsonicDecisionHttpFailure(it.code)
                            val payload = it.body?.charStream()?.use { reader ->
                                val out = StringBuilder()
                                val chars = CharArray(4096)
                                while (true) {
                                    val read = reader.read(chars)
                                    if (read < 0) break
                                    if (out.length + read > 65_536) throw IOException("Decision response too large")
                                    out.append(chars, 0, read)
                                }
                                out.toString()
                            } ?: throw IOException("Missing decision response")
                            if (continuation.isActive) continuation.resume(payload)
                        }
                    } catch (failure: OpenSubsonicDecisionHttpFailure) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (_: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("OpenSubsonic decision transport unavailable"))
                        }
                    }
                }
            })
        }
}

internal class OpenSubsonicDecisionHttpFailure(val status: Int) :
    IOException("OpenSubsonic decision rejected")

internal enum class OpenSubsonicTranscodeDecisionRejection {
    INVALID_VARIANT,
    STALE_CAPABILITY,
    DECODER_UNAVAILABLE,
    SERVER_DENIED,
    UNSUPPORTED_OUTPUT,
    MALFORMED_DECISION,
    AUTHENTICATION_REJECTED,
    MEDIA_NOT_FOUND,
    RATE_LIMITED,
    SERVER_FAILURE,
    TRANSPORT_UNAVAILABLE,
}

internal sealed interface OpenSubsonicTranscodeDecisionOutcome {
    class Ready internal constructor(
        internal val ticket: OpenSubsonicTranscodeStreamTicket,
    ) : OpenSubsonicTranscodeDecisionOutcome {
        val recordingId: String get() = ticket.recordingId
        val mediaAssetId: String get() = ticket.mediaAssetId
        val providerKey: String get() = ticket.providerKey

        override fun toString(): String = "TranscodeDecisionReady(<redacted>)"
    }

    data class Rejected(val reason: OpenSubsonicTranscodeDecisionRejection) :
        OpenSubsonicTranscodeDecisionOutcome
}

/**
 * One-use secret/opaque server token; never copy to MediaItem, Queue or logs.
 * The already URL-escaped transcodeParams value must be used as-is.
 */
internal class OpenSubsonicTranscodeStreamTicket internal constructor(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    private val runtime: OpenSubsonicRuntimeAudioRequest,
    private val serverEscapedParams: String,
) {
    internal fun accountIsCurrent(): Boolean = runtime.accountIsCurrent()
    internal fun accountRevisionIsCurrent(): Boolean = runtime.accountRevisionIsCurrent()

    internal fun materializeAuthenticatedDataSpec(logical: DataSpec): DataSpec {
        if (!runtime.accountIsCurrent()) {
            throw IOException("OpenSubsonic account changed before stream materialization")
        }
        val uri = Uri.parse(runtime.endpointUrl.replaceSuffixOrNull(
            "/stream.view", "/getTranscodeStream.view",
        ) ?: throw IOException("Invalid private stream endpoint"))
            .buildUpon()
        runtime.materializeTranscodeStreamParameters().forEach { (key, value) ->
            uri.appendQueryParameter(key, value)
        }
        val encodedQuery = uri.build().encodedQuery.orEmpty()
        uri.encodedQuery(encodedQuery + "&transcodeParams=" + serverEscapedParams)
        return logical.buildUpon().setUri(uri.build()).build()
    }

    override fun toString(): String = "OpenSubsonicTranscodeStreamTicket(<redacted>)"
}

private fun String.replaceSuffixOrNull(old: String, replacement: String): String? =
    if (endsWith(old)) removeSuffix(old) + replacement else null

/**
 * Requesting a transcode is NOT negotiation. Only a server-positive decision
 * with an HTTP/MP3 target supported by the actual client may open a ticket.
 */
internal class OpenSubsonicTranscodeDecisionEngine(
    private val transport: OpenSubsonicDecisionTransport,
) {
    suspend fun negotiate(
        runtime: OpenSubsonicRuntimeAudioRequest,
        variant: OpenSubsonicStreamVariant,
        capability: OpenSubsonicStreamCapabilitySnapshot,
        savedConfig: OpenSubsonicAccountConfig?,
        decoderSupports: (OpenSubsonicDecoderProfile) -> Boolean,
    ): OpenSubsonicTranscodeDecisionOutcome {
        fun reject(reason: OpenSubsonicTranscodeDecisionRejection) =
            OpenSubsonicTranscodeDecisionOutcome.Rejected(reason)

        if (!runtime.accountIsCurrent()) {
            return reject(OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY)
        }
        if (variant.mode != OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED ||
            runtime.recordingId != variant.recordingId ||
            runtime.mediaAssetId != variant.mediaAssetId ||
            runtime.providerKey != variant.providerKey
        ) return reject(OpenSubsonicTranscodeDecisionRejection.INVALID_VARIANT)

        if (!capability.matches(variant.accountGeneration, savedConfig) ||
            capability.transcoding.confidence !in setOf(
                OpenSubsonicStreamConfidence.ADVERTISED,
                OpenSubsonicStreamConfidence.VERIFIED,
            )
        ) return reject(OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY)

        val profile = variant.decoderProfile
            ?: return reject(OpenSubsonicTranscodeDecisionRejection.DECODER_UNAVAILABLE)
        val canDecode = try {
            decoderSupports(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        if (!canDecode) return reject(OpenSubsonicTranscodeDecisionRejection.DECODER_UNAVAILABLE)

        val maxBps = variant.maxBitrateBps
            ?: return reject(OpenSubsonicTranscodeDecisionRejection.INVALID_VARIANT)
        if (maxBps !in 32_000..512_000) {
            return reject(OpenSubsonicTranscodeDecisionRejection.INVALID_VARIANT)
        }
        val endpoint = runtime.endpointUrl.replaceSuffixOrNull(
            "/stream.view", "/getTranscodeDecision.view",
        ) ?: return reject(OpenSubsonicTranscodeDecisionRejection.INVALID_VARIANT)

        val raw = try {
            transport.post(endpoint, runtime.materializeTranscodeDecisionParameters(), clientInfo(profile, maxBps))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OpenSubsonicDecisionHttpFailure) {
            return reject(when (failure.status) {
                401, 403 -> OpenSubsonicTranscodeDecisionRejection.AUTHENTICATION_REJECTED
                404 -> OpenSubsonicTranscodeDecisionRejection.MEDIA_NOT_FOUND
                429 -> OpenSubsonicTranscodeDecisionRejection.RATE_LIMITED
                in 500..599 -> OpenSubsonicTranscodeDecisionRejection.SERVER_FAILURE
                else -> OpenSubsonicTranscodeDecisionRejection.TRANSPORT_UNAVAILABLE
            })
        } catch (_: Throwable) {
            // No exception body, URL, headers, auth or sourceKey may escape.
            return reject(OpenSubsonicTranscodeDecisionRejection.TRANSPORT_UNAVAILABLE)
        }
        // The POST may suspend while a credential/endpoint is replaced.
        // Never turn a positive response from a revoked account into a ticket.
        if (!runtime.accountIsCurrent()) {
            return reject(OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY)
        }
        val decision = try {
            val envelope = Json.parseToJsonElement(raw).jsonObject["subsonic-response"]?.jsonObject
                ?: return reject(OpenSubsonicTranscodeDecisionRejection.MALFORMED_DECISION)
            if (envelope["status"]?.jsonPrimitive?.content != "ok") {
                return reject(OpenSubsonicTranscodeDecisionRejection.SERVER_DENIED)
            }
            envelope["transcodeDecision"]?.jsonObject
                ?: return reject(OpenSubsonicTranscodeDecisionRejection.MALFORMED_DECISION)
        } catch (_: Throwable) {
            return reject(OpenSubsonicTranscodeDecisionRejection.MALFORMED_DECISION)
        }
        val canTranscode = try { decision["canTranscode"]?.jsonPrimitive?.booleanOrNull } catch (_: Throwable) { null }
        if (canTranscode == false) return reject(OpenSubsonicTranscodeDecisionRejection.SERVER_DENIED)
        if (canTranscode != true) return reject(OpenSubsonicTranscodeDecisionRejection.MALFORMED_DECISION)

        val target = try { decision["transcodeStream"]?.jsonObject } catch (_: Throwable) { null }
            ?: return reject(OpenSubsonicTranscodeDecisionRejection.UNSUPPORTED_OUTPUT)
        val supported = try {
            target["protocol"]?.jsonPrimitive?.content.equals("http", ignoreCase = true) &&
                target["container"]?.jsonPrimitive?.content.equals(profile.format, ignoreCase = true) &&
                target["codec"]?.jsonPrimitive?.content.equals(profile.format, ignoreCase = true) &&
                (target["audioBitrate"]?.jsonPrimitive?.intOrNull ?: -1) in 1..maxBps
        } catch (_: Throwable) {
            false
        }
        if (!supported) return reject(OpenSubsonicTranscodeDecisionRejection.UNSUPPORTED_OUTPUT)
        val escaped = try { decision["transcodeParams"]?.jsonPrimitive?.content.orEmpty() } catch (_: Throwable) { "" }
        if (!validEscapedOpaque(escaped)) {
            return reject(OpenSubsonicTranscodeDecisionRejection.MALFORMED_DECISION)
        }
        if (!runtime.accountIsCurrent()) {
            return reject(OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY)
        }
        return OpenSubsonicTranscodeDecisionOutcome.Ready(
            OpenSubsonicTranscodeStreamTicket(
                variant.recordingId, variant.mediaAssetId, variant.providerKey,
                runtime = runtime, serverEscapedParams = escaped,
            ),
        )
    }

    private fun validEscapedOpaque(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 8192) return false
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%') {
                if (index + 2 >= raw.length ||
                    raw[index + 1].digitToIntOrNull(16) == null ||
                    raw[index + 2].digitToIntOrNull(16) == null
                ) return false
                index += 3
            } else {
                if (!ch.isLetterOrDigit() || ch.code > 127) {
                    if (ch !in "-._~") return false
                }
                index++
            }
        }
        return true
    }

    private fun clientInfo(profile: OpenSubsonicDecoderProfile, maxBps: Int): String =
        buildJsonObject {
            put("name", "BitChord")
            put("platform", "Android")
            put("maxAudioBitrate", maxBps)
            put("maxTranscodingAudioBitrate", maxBps)
            putJsonArray("directPlayProfiles") {
                add(buildJsonObject {
                    putJsonArray("containers") { add(profile.format) }
                    putJsonArray("audioCodecs") { add(profile.format) }
                    putJsonArray("protocols") { add("http") }
                    put("maxAudioChannels", 2)
                })
            }
            putJsonArray("transcodingProfiles") {
                add(buildJsonObject {
                    put("container", profile.format)
                    put("audioCodec", profile.format)
                    put("protocol", "http")
                    put("maxAudioChannels", 2)
                })
            }
            putJsonArray("codecProfiles") { }
        }.toString()
}
