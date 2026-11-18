package com.music.bitchord.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderRequestTicket
import java.io.IOException

/**
 * A provider-neutral, in-memory authorization proof for source-owned HTTP bytes.
 * DataSpec.customData does not become an HTTP header, URI, cache key or log.
 * The lease names the exact provider *instance* that won stream resolution.
 */
internal class ProviderPlaybackByteLease(
    private val gate: ProviderPolicyGate,
    private val ticket: ProviderRequestTicket,
) {
    /** Non-secret instance identity for tagging temporary provider-owned cache. */
    val providerKey: ProviderKey get() = ticket.key
    fun allowed(): Boolean = gate.accept(ticket)
    fun onRevoke(callback: () -> Unit): () -> Unit = gate.onRevoke(ticket, callback)
}

/**
 * The Media3 OkHttpDataSource.close() path cannot cancel a Call whose HTTP
 * headers have not arrived yet: there is no established response stream to
 * close. Hold the actual OkHttp Call per Media3 data source and cancel it even
 * during the initial pending open. A revoked session cannot create a later
 * network Call; sibling data sources have separate factories and are untouched.
 */
internal class ProviderPlaybackCallFactory(
    private val upstream: Call.Factory,
) : Call.Factory {
    private val lock = Any()
    private var revoked = false
    private var restrictRedirects = false
    private var latest: Call? = null

    override fun newCall(request: Request): Call {
        val guarded = synchronized(lock) { restrictRedirects }
        val call = if (guarded) {
            // A source may supply arbitrary HTTP headers. OkHttp strips the
            // built-in Authorization header when a host changes, but custom
            // tokens/cookies can otherwise cross redirect boundaries. Apply
            // this check at every network hop, BEFORE sending the request.
            // Preserve same-origin redirects; ordinary non-provider URLs keep
            // their original OkHttp policy without a cloned client.
            val client = upstream as? OkHttpClient
                ?: throw IOException("Guarded playback requires a controlled HTTP transport")
            val original = request.url
            client.newBuilder().addNetworkInterceptor { chain ->
                val next = chain.request().url
                if (next.scheme != original.scheme ||
                    next.host != original.host ||
                    next.port != original.port) {
                    throw IOException("Provider HTTP redirect outside authorized origin")
                }
                chain.proceed(chain.request())
            }.build().newCall(request)
        } else upstream.newCall(request)
        synchronized(lock) {
            if (revoked) call.cancel()
            else latest = call
        }
        return call
    }

    fun revoke() {
        val pending = synchronized(lock) {
            revoked = true
            latest.also { latest = null }
        }
        pending?.cancel()
    }

    /**
     * Media3 reuses a DataSource object after close() (e.g. range reopen,
     * seek and next item). A closed transfer is never allowed to poison a
     * future, independently authorized open; re-arm the Call factory only
     * for that new open. A stale ticket is checked separately by the lease.
     */
    fun beginOpen(protectedProvider: Boolean) = synchronized(lock) {
        revoked = false
        restrictRedirects = protectedProvider
        latest = null
    }
}

/**
 * Sits AFTER ResolvingDataSource and BEFORE the HTTP/ChunkedDataSource chain.
 * Stream URL discovery alone is not permission to continue downloading bytes:
 * disabling the source must cancel an already open HTTP call and suppress
 * any bytes that race in immediately after revocation.
 *
 * Non-extension requests are passed through untouched (local media and
 * OpenSubsonic use their own playback transports).
 */
@OptIn(UnstableApi::class)
internal class ProviderPlaybackByteDataSource(
    private val upstream: DataSource,
    private val cancelNetwork: () -> Unit = {},
    private val beginNetworkOpen: (Boolean) -> Unit = {},
    private val fallbackLease: () -> ProviderPlaybackByteLease? = { null },
    private val requireLease: Boolean = false,
) : DataSource {
    @Volatile private var revoked = false
    private var lease: ProviderPlaybackByteLease? = null
    private var unsubscribe: (() -> Unit)? = null
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("Playback byte transport already open")
        // DASH/HLS segments arrive as new HTTP DataSpecs without the
        // initial logical manifest's customData. A scoped MediaSource factory
        // supplies its own immutable winning-provider session for those hops.
        val permission = (dataSpec.customData as? ProviderPlaybackByteLease)
            ?: fallbackLease()
        beginNetworkOpen(permission != null)
        revoked = false
        if (requireLease && permission == null)
            throw IOException("Adaptive playback requires a provider media-session ticket")
        lease = permission
        if (permission != null) {
            if (!permission.allowed()) {
                lease = null
                throw IOException("Playback source capability revoked")
            }
            unsubscribe = permission.onRevoke {
                revoked = true
                // Cancel the actual OkHttp Call first; Media3.close() alone
                // cannot abort a pending open before response headers arrive.
                cancelNetwork()
                runCatching { upstream.close() }
            }
            if (revoked || !permission.allowed()) {
                close()
                throw IOException("Playback source capability revoked")
            }
        }
        opened = true
        return try {
            val count = upstream.open(dataSpec)
            checkAuthorized()
            count
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("Playback byte transport is not open")
        checkAuthorized()
        val count = upstream.read(buffer, offset, length)
        checkAuthorized()
        return count
    }

    private fun checkAuthorized() {
        if (revoked || lease?.allowed() == false)
            throw IOException("Playback source capability revoked")
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders

    override fun close() {
        opened = false
        unsubscribe?.invoke()
        unsubscribe = null
        lease = null
        cancelNetwork()
        upstream.close()
    }

    internal class Factory(
        private val calls: Call.Factory,
        private val chunkBytes: Long,
        private val fallbackLease: () -> ProviderPlaybackByteLease? = { null },
        private val requireLease: Boolean = false,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val scopedCalls = ProviderPlaybackCallFactory(calls)
            return ProviderPlaybackByteDataSource(
                ChunkedDataSource(
                    OkHttpDataSource.Factory(scopedCalls).createDataSource(), chunkBytes,
                ),
                cancelNetwork = scopedCalls::revoke,
                beginNetworkOpen = scopedCalls::beginOpen,
                fallbackLease = fallbackLease,
                requireLease = requireLease,
            )
        }
    }
}
