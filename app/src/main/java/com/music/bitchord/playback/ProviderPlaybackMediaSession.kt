package com.music.bitchord.playback

import java.io.IOException
import java.net.URI
import java.util.Locale
import androidx.media3.datasource.DataSpec

/**
 * One Media3 media item may generate a manifest request plus hundreds of
 * independent HLS/DASH segment DataSpecs. Media3 does not copy the initial
 * customData into each segment; all of them must inherit the exact provider
 * authority of their *own* media item, not a process-global latest stream.
 *
 * This session exists only as long as the enclosing MediaSource's factory.
 * Active segment DataSources subscribe individually to the ticket's revocation
 * and physically cancel their own OkHttp Call. No global URL map is involved.
 */
internal class ProviderPlaybackMediaSession {
    private data class Origin(val scheme: String, val host: String, val port: Int)

    @Volatile private var lease: ProviderPlaybackByteLease? = null
    @Volatile private var manifestOrigin: Origin? = null
    @Volatile private var originalHeaders: Map<String, String> = emptyMap()

    /**
     * Bind only the winning provider of this media item. The manifest's own
     * request headers may be needed by sibling HLS/DASH segments on the SAME
     * origin, but must never be forwarded to a third-party CDN.
     *
     * Cache-only manifest hits have no resolved origin/headers in memory:
     * they preserve the ticket, but never invent or replay cached secrets.
     */
    @Synchronized fun bind(
        incoming: ProviderPlaybackByteLease,
        manifestUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (!incoming.allowed())
            throw IOException("Resolved media provider no longer authorized")
        val previous = lease
        if (previous != null && previous.providerKey != incoming.providerKey)
            throw IOException("Adaptive media item cannot switch provider instances mid-stream")
        if (manifestUrl != null) {
            manifestOrigin = originOf(manifestUrl)
                ?: throw IOException("Provider manifest origin is not HTTP(S)")
            originalHeaders = headers.toMap()
        } else {
            // A fully cached manifest supplies provenance, never current
            // credentials or a verified CDN origin. Dropping stale secrets
            // also protects a reenabled or rotated account on the same
            // provider instance from inheriting the previous session.
            manifestOrigin = null
            originalHeaders = emptyMap()
        }
        lease = incoming
    }

    /**
     * Segment requests have no DataSpec.customData and Media3 does not copy
     * the resolved manifest's HTTP request headers. This operation is scoped
     * to the immutable media-item factory and requires an active ticket.
     */
    fun withSegmentHeaders(spec: DataSpec): DataSpec {
        val current = lease?.takeIf { it.allowed() }
            ?: throw IOException("Provider media session is not authorized")
        // Make sure the ticket is still valid after reading the header policy.
        val headers = originalHeaders
        if (headers.isEmpty() || originOf(spec.uri.toString()) != manifestOrigin)
            return spec
        if (!current.allowed())
            throw IOException("Provider media session revoked before segment request")
        // HTTP field names are case-insensitive. A segment's explicitly
        // supplied header must win even if it spells the key differently;
        // otherwise both values might be sent in one network request.
        val explicit = spec.httpRequestHeaders
        val inherited = headers.filterKeys { name ->
            explicit.keys.none { it.equals(name, ignoreCase = true) }
        }
        return spec.buildUpon()
            .setHttpRequestHeaders(inherited + explicit)
            .build()
    }

    fun currentLease(): ProviderPlaybackByteLease? = lease

    private fun originOf(url: String): Origin? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return@runCatching null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        Origin(scheme, host, port)
    }.getOrNull()
}
