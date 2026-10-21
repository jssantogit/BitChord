package com.music.bitchord.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRevisionClock
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamLeaseStatus
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamPreparation
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamTruthSnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.androidOpenSubsonicPlaybackStreamAdapter
import com.music.bitchord.data.settings.AudioQuality
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A pure stream candidate is not server negotiation, HTTP bytes or audible output. */
internal enum class CanonicalRemoteStreamMode {
    ORIGINAL, MP3_REQUESTED_UNVERIFIED, REJECTED,
}
internal enum class CanonicalRemoteStreamRejection {
    STALE_ACCOUNT, UNSUPPORTED_CEILING, CAPABILITY_UNAVAILABLE, PREPARATION_UNAVAILABLE,
}
internal class CanonicalRemoteStreamPreparation(
    val mode: CanonicalRemoteStreamMode,
    val source: DataSource? = null,
    val rejection: CanonicalRemoteStreamRejection? = null,
    val requestedBitrateBps: Int? = null,
) {
    val applied: Boolean get() = false
    val negotiatedFormat: String? get() = null
    val observedFormat: String? get() = null
    override fun toString(): String =
        "CanonicalRemoteStreamPreparation(mode=$mode, rejected=$rejection, applied=false)"
}

/**
 * Catalog owns the account revision, capability discovery, per-MediaAsset decision,
 * all authenticated transport and stream truth. Only canonical identities cross.
 */
internal interface CanonicalRemoteQualityCatalog {
    fun accountGeneration(): Long
    suspend fun refreshCapability(
        generation: Long, providerKey: String,
    ): CanonicalRemoteTranscodeCapability
    fun prepareOriginal(
        request: CanonicalRemotePlaybackRequest,
        generation: Long,
        onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
    ): DataSource?
    fun prepareMp3(
        request: CanonicalRemotePlaybackRequest,
        generation: Long,
        ceilingBps: Int,
        onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
    ): DataSource?
}

/**
 * Runs once per next logical open, never in a player callback or recomposition.
 * ADVERTISED is permission to PREPARE an unverified request only. C11 verifies
 * the actual MediaAsset at DataSource.open before any private stream bytes.
 */
internal class CanonicalRemoteQualityRuntime(
    private val catalog: CanonicalRemoteQualityCatalog,
) {
    suspend fun prepare(
        request: CanonicalRemotePlaybackRequest,
        preferences: CanonicalRemoteQualityPreferences,
        network: CanonicalRemoteNetwork,
        onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit = {},
    ): CanonicalRemoteStreamPreparation {
        fun reject(reason: CanonicalRemoteStreamRejection) =
            CanonicalRemoteStreamPreparation(CanonicalRemoteStreamMode.REJECTED, rejection = reason)
        val generation = catalog.accountGeneration()
        if (generation < 0L) return reject(CanonicalRemoteStreamRejection.STALE_ACCOUNT)
        val chosen = when (network) {
            CanonicalRemoteNetwork.WIFI -> preferences.wifi
            CanonicalRemoteNetwork.MOBILE -> preferences.mobile
            CanonicalRemoteNetwork.UNKNOWN, CanonicalRemoteNetwork.OTHER -> null
        }
        // UNKNOWN transport and explicit lossless mean raw/original. They never
        // cause manifest discovery or touch the currently playing Media3 item.
        if (chosen == null || chosen == AudioQuality.LOSSLESS) {
            val direct = try {
                catalog.prepareOriginal(request, generation, onTruth)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (generation != catalog.accountGeneration()) {
                runCatching { direct?.close() }
                return reject(CanonicalRemoteStreamRejection.STALE_ACCOUNT)
            }
            return direct?.let {
                CanonicalRemoteStreamPreparation(CanonicalRemoteStreamMode.ORIGINAL, source = it)
            } ?: reject(CanonicalRemoteStreamRejection.PREPARATION_UNAVAILABLE)
        }
        // The existing persisted Low policy is 64 kbps, whereas the integrated
        // Catalog C13 adapter only accepts 96/160/320. Refuse instead of
        // silently sending 96, or falling back to bandwidth-unbounded raw.
        if (chosen == AudioQuality.LOW) {
            return reject(CanonicalRemoteStreamRejection.UNSUPPORTED_CEILING)
        }
        val capability = try {
            catalog.refreshCapability(generation, request.providerKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return reject(CanonicalRemoteStreamRejection.CAPABILITY_UNAVAILABLE)
        }
        if (generation != catalog.accountGeneration()) {
            return reject(CanonicalRemoteStreamRejection.STALE_ACCOUNT)
        }
        // B13's next-open gate is separate from B12's read-only preview:
        // ADVERTISED permits only a pending request, not per-asset verification.
        val openPlan = CanonicalRemoteQualityOpenGate.prepareNextOpen(
            chosen, capability, generation,
        )
        if (openPlan.mode != CanonicalRemoteQualityOpenMode.REQUEST_CAPPED_MP3) {
            return reject(CanonicalRemoteStreamRejection.CAPABILITY_UNAVAILABLE)
        }
        val ceiling = requireNotNull(openPlan.maxBitrateBps)
        val prepared = try {
            catalog.prepareMp3(request, generation, ceiling, onTruth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (generation != catalog.accountGeneration()) {
            runCatching { prepared?.close() }
            return reject(CanonicalRemoteStreamRejection.STALE_ACCOUNT)
        }
        return prepared?.let {
            CanonicalRemoteStreamPreparation(
                CanonicalRemoteStreamMode.MP3_REQUESTED_UNVERIFIED,
                source = it,
                requestedBitrateBps = ceiling,
            )
        } ?: reject(CanonicalRemoteStreamRejection.PREPARATION_UNAVAILABLE)
    }
}

/** Android composition uses only existing Catalog-owned public-private seams. */
@UnstableApi
internal class AndroidCanonicalRemoteQualityCatalog(context: Context) : CanonicalRemoteQualityCatalog {
    private val adapter = androidOpenSubsonicPlaybackStreamAdapter(context.applicationContext)

    override fun accountGeneration(): Long = OpenSubsonicAccountRevisionClock.current()

    override suspend fun refreshCapability(
        generation: Long, providerKey: String,
    ): CanonicalRemoteTranscodeCapability {
        val handoff = adapter.refreshCapabilities(generation, providerKey)
        if (handoff.lifecycle != OpenSubsonicStreamLeaseStatus.CURRENT) {
            return CanonicalRemoteTranscodeCapability.UNKNOWN
        }
        return when (handoff.transcoding) {
            OpenSubsonicStreamConfidence.ADVERTISED ->
                CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY
            // The handoff carries no MediaAsset ID or decoder-profile proof.
            // Even a VERIFIED account-level hint cannot become B12's
            // per-MediaAsset VERIFIED_MP3 until Catalog's actual open decision.
            OpenSubsonicStreamConfidence.VERIFIED ->
                CanonicalRemoteTranscodeCapability.ADVERTISED_ONLY
            OpenSubsonicStreamConfidence.UNSUPPORTED ->
                CanonicalRemoteTranscodeCapability.UNSUPPORTED
            OpenSubsonicStreamConfidence.UNKNOWN ->
                CanonicalRemoteTranscodeCapability.UNKNOWN
        }
    }

    override fun prepareOriginal(
        request: CanonicalRemotePlaybackRequest, generation: Long,
        onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
    ): DataSource? = when (val result = adapter.prepareOriginal(
        request.recordingId, request.mediaAssetId, request.providerKey, generation, onTruth,
    )) {
        is OpenSubsonicStreamPreparation.Ready -> result.dataSource
        is OpenSubsonicStreamPreparation.Rejected -> null
    }

    override fun prepareMp3(
        request: CanonicalRemotePlaybackRequest, generation: Long, ceilingBps: Int,
        onTruth: (OpenSubsonicStreamTruthSnapshot) -> Unit,
    ): DataSource? = when (val result = adapter.prepareCappedMp3(
        request.recordingId, request.mediaAssetId, request.providerKey,
        generation, ceilingBps, onTruth,
    )) {
        is OpenSubsonicStreamPreparation.Ready -> result.dataSource
        is OpenSubsonicStreamPreparation.Rejected -> null
    }
}

/** Physical network transport is never inferred from metering or LAN reachability. */
internal object CanonicalRemotePhysicalNetwork {
    fun snapshot(context: Context): CanonicalRemoteNetwork {
        return try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return CanonicalRemoteNetwork.UNKNOWN
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return CanonicalRemoteNetwork.UNKNOWN
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CanonicalRemoteNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CanonicalRemoteNetwork.MOBILE
            else -> CanonicalRemoteNetwork.OTHER
        }
        } catch (_: Exception) {
            CanonicalRemoteNetwork.UNKNOWN
        }
    }
}

/**
 * Atomic, terminal gate for sanitized Catalog truth callbacks. A callback
 * racing with close() must never reinstate a snapshot after the session ends.
 */
internal class CanonicalRemoteTruthGate<T> {
    private val monitor = Any()
    private var closed = false
    private var current: T? = null

    fun publish(snapshot: T) = synchronized(monitor) {
        if (!closed) current = snapshot
    }

    fun latest(): T? = synchronized(monitor) { current }

    fun close() = synchronized(monitor) {
        closed = true
        current = null
    }
}

/**
 * Actual Media3 boundary. Planning and private Catalog resolution occur only
 * from open(DataSpec); construction, queue edits and network changes do nothing.
 * close() cancels pending discovery and ignores callbacks from a closed open.
 */
internal class CanonicalRemoteQualityDataSource(
    private val request: CanonicalRemotePlaybackRequest,
    private val runtime: CanonicalRemoteQualityRuntime,
    private val preferences: () -> CanonicalRemoteQualityPreferences,
    private val network: () -> CanonicalRemoteNetwork,
) : DataSource {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<TransferListener>()
    private val openJob = Job()
    @Volatile private var closed = false
    @Volatile private var active: DataSource? = null
    private var opening = false
    private val truthGate = CanonicalRemoteTruthGate<OpenSubsonicStreamTruthSnapshot>()
    internal val latestTruth: OpenSubsonicStreamTruthSnapshot?
        get() = truthGate.latest()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        synchronized(lock) {
            if (closed || opening || active != null) throw IOException("Remote quality stream unavailable")
            opening = true
        }
        val logical = CanonicalRemotePlaybackUri.decode(dataSpec.uri.toString())
        if (logical != request) {
            synchronized(lock) { opening = false }
            throw IOException("Remote quality identity changed")
        }
        val prepared = try {
            runBlocking(openJob) {
                withTimeout(15_000L) {
                    runtime.prepare(request, preferences(), network()) {
                        truthGate.publish(it)
                    }
                }
            }
        } catch (error: CancellationException) {
            synchronized(lock) { opening = false }
            throw InterruptedIOException("Remote quality open cancelled")
        } catch (_: Exception) {
            synchronized(lock) { opening = false }
            throw IOException("Remote quality stream unavailable")
        }
        val delegate = prepared.source ?: run {
            synchronized(lock) { opening = false }
            throw IOException("Remote quality unavailable: " +
                (prepared.rejection?.name ?: "PREPARATION_UNAVAILABLE"))
        }
        synchronized(lock) {
            if (closed || openJob.isCancelled) {
                opening = false
                runCatching { delegate.close() }
                throw InterruptedIOException("Remote quality open cancelled")
            }
            active = delegate
            opening = false
            listeners.forEach(delegate::addTransferListener)
        }
        return try {
            val length = delegate.open(dataSpec)
            if (closed || openJob.isCancelled) throw InterruptedIOException("Remote quality open cancelled")
            length
        } catch (error: Exception) {
            synchronized(lock) { active = null }
            runCatching { delegate.close() }
            when (error) {
                is InterruptedIOException -> throw InterruptedIOException("Remote quality open interrupted")
                else -> throw IOException("Remote quality stream unavailable")
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val delegate = active ?: throw IOException("Remote quality stream is not open")
        if (closed) throw InterruptedIOException("Remote quality stream cancelled")
        return try {
            val read = delegate.read(buffer, offset, length)
            if (closed) throw InterruptedIOException("Remote quality stream cancelled")
            read
        } catch (error: Exception) {
            if (error is InterruptedIOException) throw error
            throw IOException("Remote quality stream unavailable")
        }
    }

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders.orEmpty()

    override fun close() {
        val previous = synchronized(lock) {
            closed = true
            truthGate.close()
            openJob.cancel()
            val before = active
            active = null
            before
        }
        previous?.close()
    }

    override fun toString(): String = "CanonicalRemoteQualityDataSource(<redacted>)"
}
