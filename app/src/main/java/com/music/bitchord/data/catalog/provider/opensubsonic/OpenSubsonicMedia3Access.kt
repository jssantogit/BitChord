package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.bitchord.data.Http
import com.music.bitchord.diagnostics.BoundedPerformanceProbe
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.BuildConfig
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.observability.StructuredTracer
import com.music.bitchord.observability.TraceFeature
import com.music.bitchord.observability.TraceOutcome
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Provider-owned validation gate between canonical identity and the opaque
 * runtime request. Playback never receives [OpenSubsonicRuntimeAudioRequest].
 */
internal class OpenSubsonicRuntimeTransport(
    private val performanceProbe: BoundedPerformanceProbe = BoundedPerformanceProbe(),
    private val tracer: StructuredTracer? = null,
    private val negotiateVariant: (suspend (OpenSubsonicRuntimeAudioRequest, OpenSubsonicStreamVariant) -> OpenSubsonicTranscodeDecisionOutcome)? = null,
    private val resolve: suspend (mediaAssetId: String) -> OpenSubsonicMediaAccessResult,
) {
    suspend fun resolveRuntime(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
    ): OpenSubsonicRuntimeAudioRequest {
        // Provider owns identity validation and opaque authenticated requests. Only
        // the private-salt references and typed stages may leave that boundary.
        val trace = tracer?.let { activeTracer ->
            activeTracer.startTrace(
                feature = TraceFeature.PROVIDER,
                operation = "opensubsonic_runtime_resolve",
                attributes = mapOf(
                    "recordingRef" to activeTracer.reference(recordingId),
                    "assetRef" to activeTracer.reference(mediaAssetId),
                    "providerRef" to activeTracer.reference(providerKey),
                ),
            )
        }
        return try {
            val result = try {
                val lookup: suspend () -> OpenSubsonicMediaAccessResult = {
                    performanceProbe.measureSuspend(
                        category = DiagnosticCategory.NETWORK,
                        name = "runtime_stream_resolution",
                        metadata = mapOf("provider" to "opensubsonic"),
                    ) {
                        resolve(mediaAssetId)
                    }
                }
                if (trace == null) lookup() else trace.spanSuspend("access_lookup", block = lookup)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw IOException("Remote playback access unavailable")
            }

            val runtime = when (result) {
                is OpenSubsonicMediaAccessResult.Failure -> throw safeFailure(result.reason)
                is OpenSubsonicMediaAccessResult.Ready -> {
                    val request = result.request
                    if (
                        request.recordingId != recordingId ||
                        request.mediaAssetId != mediaAssetId ||
                        request.providerKey != providerKey
                    ) {
                        throw IOException("Canonical remote playback identity mismatch")
                    }
                    request
                }
            }
            trace?.event("identity_verified", TraceOutcome.SUCCEEDED)
            trace?.complete(TraceOutcome.SUCCEEDED)
            runtime
        } catch (cancelled: CancellationException) {
            trace?.complete(TraceOutcome.CANCELLED)
            throw cancelled
        } catch (error: Throwable) {
            trace?.complete(TraceOutcome.FAILED)
            throw error
        }
    }

    /** Negotiate only at DataSource.open, after fresh canonical identity verification. */
    internal suspend fun resolveRuntimeVariant(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        variant: OpenSubsonicStreamVariant,
    ): OpenSubsonicTranscodeDecisionOutcome {
        if (variant.recordingId != recordingId ||
            variant.mediaAssetId != mediaAssetId ||
            variant.providerKey != providerKey
        ) return OpenSubsonicTranscodeDecisionOutcome.Rejected(
            OpenSubsonicTranscodeDecisionRejection.INVALID_VARIANT,
        )
        val negotiator = negotiateVariant ?: return OpenSubsonicTranscodeDecisionOutcome.Rejected(
            OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY,
        )
        val runtime = resolveRuntime(recordingId, mediaAssetId, providerKey)
        return try {
            negotiator(runtime, variant)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            OpenSubsonicTranscodeDecisionOutcome.Rejected(
                OpenSubsonicTranscodeDecisionRejection.TRANSPORT_UNAVAILABLE,
            )
        }
    }

    private fun safeFailure(reason: OpenSubsonicMediaAccessFailure): IOException = IOException(
        when (reason) {
            OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND -> "Remote media asset unavailable"
            OpenSubsonicMediaAccessFailure.UNSUPPORTED_PROVIDER -> "Remote playback provider unsupported"
            OpenSubsonicMediaAccessFailure.ACCOUNT_NOT_CONFIGURED -> "Remote playback account unavailable"
            OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH -> "Remote playback account mismatch"
            OpenSubsonicMediaAccessFailure.CREDENTIAL_UNAVAILABLE -> "Remote playback credential unavailable"
            OpenSubsonicMediaAccessFailure.INVALID_LOCATOR -> "Remote media locator unavailable"
        },
    )
}

/**
 * Provider-owned Media3 transport for one canonical OpenSubsonic asset.
 *
 * The authenticated HTTP [DataSpec] exists only inside [open]. It is handed
 * directly to the private OkHttp upstream and never to Playback's generic
 * ResolvingDataSource, ChunkedDataSource, cache, telemetry or transfer
 * listeners. Anything observable outside this class uses the original logical
 * credential-free DataSpec.
 */
@UnstableApi
internal class OpenSubsonicMediaDataSource(
    private val recordingId: String,
    private val mediaAssetId: String,
    private val providerKey: String,
    private val transport: OpenSubsonicRuntimeTransport,
    private val upstreamFactory: DataSource.Factory,
    private val variant: OpenSubsonicStreamVariant? = null,
) : DataSource {
    private val transferListeners = CopyOnWriteArrayList<TransferListener>()
    private var upstream: DataSource? = null
    private var logicalDataSpec: DataSpec? = null
    private var transferStarted = false

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        if (upstream != null) throw IOException("OpenSubsonic stream already open")
        logicalDataSpec = dataSpec
        transferListeners.forEach { it.onTransferInitializing(this, dataSpec, true) }

        val authenticatedSpec = try {
            when (variant?.mode) {
                null, OpenSubsonicStreamVariantMode.ORIGINAL -> {
                    val runtime = runBlocking {
                        transport.resolveRuntime(
                            recordingId = recordingId,
                            mediaAssetId = mediaAssetId,
                            providerKey = providerKey,
                        )
                    }
                    runtime.materializeAuthenticatedDataSpec(dataSpec)
                }
                OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED -> {
                    // Extension offset is time-based, not a byte seek. Fail
                    // closed until seeking/range behavior has been proven.
                    if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        throw IOException("OpenSubsonic transcoded byte seek unsupported")
                    }
                    val negotiated = runBlocking {
                        transport.resolveRuntimeVariant(
                            recordingId, mediaAssetId, providerKey, requireNotNull(variant),
                        )
                    }
                    when (negotiated) {
                        is OpenSubsonicTranscodeDecisionOutcome.Ready ->
                            negotiated.ticket.materializeAuthenticatedDataSpec(dataSpec)
                        is OpenSubsonicTranscodeDecisionOutcome.Rejected ->
                            throw IOException("OpenSubsonic stream variant unavailable: " + negotiated.reason)
                    }
                }
                OpenSubsonicStreamVariantMode.LEGACY_STREAM_BEST_EFFORT ->
                    throw IOException("OpenSubsonic legacy bitrate request not activated")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("OpenSubsonic playback resolution interrupted")
        }
        val next = upstreamFactory.createDataSource()
        upstream = next
        return try {
            val length = next.open(authenticatedSpec)
            transferStarted = true
            transferListeners.forEach { it.onTransferStart(this, dataSpec, true) }
            length
        } catch (error: Exception) {
            upstream = null
            runCatching { next.close() }
            throw sanitizeTransportFailure(error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val current = upstream ?: throw IOException("OpenSubsonic stream is not open")
        return try {
            val read = current.read(buffer, offset, length)
            if (read > 0) {
                val spec = logicalDataSpec
                if (spec != null) {
                    transferListeners.forEach {
                        it.onBytesTransferred(this, spec, true, read)
                    }
                }
            }
            read
        } catch (error: Exception) {
            throw sanitizeTransportFailure(error)
        }
    }

    /** Never expose the authenticated upstream URI. */
    override fun getUri(): Uri? = logicalDataSpec?.uri

    /**
     * Return only response metadata useful to Media3. Redirect/location-style
     * headers stay provider-private because they can contain a credentialed URL.
     */
    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders
            .orEmpty()
            .filterKeys { it.lowercase() in SAFE_RESPONSE_HEADERS }

    override fun close() {
        val current = upstream
        val spec = logicalDataSpec
        upstream = null
        logicalDataSpec = null
        val hadStarted = transferStarted
        transferStarted = false

        var closeFailure: IOException? = null
        if (current != null) {
            try {
                current.close()
            } catch (error: Exception) {
                closeFailure = sanitizeTransportFailure(error)
            }
        }
        if (hadStarted && spec != null) {
            transferListeners.forEach { it.onTransferEnd(this, spec, true) }
        }
        closeFailure?.let { throw it }
    }

    private fun sanitizeTransportFailure(error: Exception): IOException = when (error) {
        is InterruptedIOException ->
            InterruptedIOException("OpenSubsonic stream interrupted")

        is HttpDataSource.InvalidResponseCodeException ->
            IOException("OpenSubsonic stream request rejected (${error.responseCode})")

        else -> IOException("OpenSubsonic stream unavailable")
    }

    private fun OpenSubsonicRuntimeAudioRequest.materializeAuthenticatedDataSpec(
        logicalDataSpec: DataSpec,
    ): DataSpec {
        val uriBuilder = Uri.parse(endpointUrl).buildUpon()
        materializeParameters().forEach { (key, value) ->
            uriBuilder.appendQueryParameter(key, value)
        }
        return logicalDataSpec.buildUpon()
            .setUri(uriBuilder.build())
            .build()
    }

    private companion object {
        val SAFE_RESPONSE_HEADERS = setOf(
            "accept-ranges",
            "content-length",
            "content-type",
            "etag",
            "last-modified",
        )
    }
}

/** Request-specific factory whose construction does not resolve auth or touch Keystore. */
@UnstableApi
internal class OpenSubsonicMediaDataSourceFactory(
    private val transport: OpenSubsonicRuntimeTransport,
    private val upstreamFactory: DataSource.Factory,
) {
    fun create(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
    ): DataSource = OpenSubsonicMediaDataSource(
        recordingId = recordingId,
        mediaAssetId = mediaAssetId,
        providerKey = providerKey,
        transport = transport,
        upstreamFactory = upstreamFactory,
    )

    /** Catalog-owned B13 seam: no side effects until Media3 DataSource.open. */
    fun createWithVariant(variant: OpenSubsonicStreamVariant): DataSource =
        OpenSubsonicMediaDataSource(
            recordingId = variant.recordingId,
            mediaAssetId = variant.mediaAssetId,
            providerKey = variant.providerKey,
            transport = transport,
            upstreamFactory = upstreamFactory,
            variant = variant,
        )
}

/** Android production composition root for provider-owned Media3 transport. */
@UnstableApi
internal fun androidOpenSubsonicMediaDataSourceFactory(
    context: Context,
): OpenSubsonicMediaDataSourceFactory {
    val appContext = context.applicationContext
    val accounts = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(appContext),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(appContext),
    )
    val access = OpenSubsonicMediaAccess(
        assets = RoomOpenSubsonicMediaAssetStore(appContext),
        accounts = accounts,
    )
    val decision = OpenSubsonicTranscodeDecisionEngine(OkHttpOpenSubsonicDecisionTransport())
    val safeStreamClient = Http.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    return OpenSubsonicMediaDataSourceFactory(
        transport = OpenSubsonicRuntimeTransport(
            tracer = if (BuildConfig.DEBUG) Diagnostics.structuredTracer() else null,
            negotiateVariant = { runtime, variant ->
                val config = accounts.savedConfig()
                if (config == null) {
                    OpenSubsonicTranscodeDecisionOutcome.Rejected(
                        OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY,
                    )
                } else {
                    val provider = OpenSubsonicProvider(config)
                    val evidence = provider.streamCapabilityEvidence(variant.accountGeneration)
                    val chosen = decision.negotiate(
                        runtime, variant, evidence, config,
                    ) { profile ->
                        when (profile) {
                            OpenSubsonicDecoderProfile.MP3_HTTP -> runCatching {
                                android.media.MediaCodecList(
                                    android.media.MediaCodecList.REGULAR_CODECS,
                                ).codecInfos.any { info ->
                                    !info.isEncoder && info.supportedTypes.any {
                                        it.equals("audio/mpeg", ignoreCase = true)
                                    }
                                }
                            }.getOrDefault(false)
                        }
                    }
                    // Check again after the suspendable POST: a prior account
                    // credential may never be used against a replaced host.
                    if (!provider.matchesSavedConfig(accounts.savedConfig())) {
                        OpenSubsonicTranscodeDecisionOutcome.Rejected(
                            OpenSubsonicTranscodeDecisionRejection.STALE_CAPABILITY,
                        )
                    } else {
                        chosen
                    }
                }
            },
            resolve = access::resolve,
        ),
        // Never send authenticated query material through a 3xx redirect.
        upstreamFactory = OkHttpDataSource.Factory(safeStreamClient),
    )
}
