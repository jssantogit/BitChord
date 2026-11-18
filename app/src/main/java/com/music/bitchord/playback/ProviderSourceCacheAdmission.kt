package com.music.bitchord.playback

import com.music.bitchord.data.providers.ProviderCapability
import java.io.IOException
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.providerSegmentCacheKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.providers.sourcePlaybackCacheFingerprint

/**
 * Cache hits skip both SourceResolver and the provider HTTP byte DataSource.
 * Never serve source-owned cached spans without the exact winning provider's
 * current EXTENSION_PLAYBACK authorization. Unknown/old provenance and partial
 * cache entries are unsafe to reuse and must be discarded before resolving.
 *
 * Returns an in-memory revocable lease, not a persisted grant.
 */
internal fun sourceCacheReadLease(
    providerId: String?,
    instanceId: String?,
    completelyCached: Boolean,
    gate: ProviderPolicyGate?,
): ProviderPlaybackByteLease? {
    if (!completelyCached || gate == null) return null
    if (providerId != "addon" && providerId != "custom_module") return null
    if (instanceId.isNullOrBlank() || instanceId.length > 128) return null
    val ticket = gate.open(
        ProviderCapability.EXTENSION_PLAYBACK,
        ProviderKey(providerId, instanceId),
    ) ?: return null
    return ProviderPlaybackByteLease(gate, ticket)
}

/**
 * A complete legacy entry can use the progressive parser only if its actual
 * bytes identify an audio container. This intentionally rejects HLS/DASH
 * manifests and unknown formats rather than guessing their transport type.
 */
internal fun hasProgressiveAudioSignature(bytes: ByteArray): Boolean {
    fun has(text: String, start: Int = 0) =
        bytes.size >= start + text.length &&
            text.indices.all { (bytes[start + it].toInt() and 255) == text[it].code }
    if (has("ID3") || has("fLaC") || has("OggS") || has("wvpk") ||
        has("MAC ") || has("MPCK") || has("DSD ")) return true
    if (has("RIFF") && has("WAVE", 8)) return true
    if (has("FORM") && (has("AIFF", 8) || has("AIFC", 8))) return true
    if (has("ftyp", 4)) return true
    if (bytes.size >= 4 &&
        (bytes[0].toInt() and 255) == 0x1a &&
        (bytes[1].toInt() and 255) == 0x45 &&
        (bytes[2].toInt() and 255) == 0xdf &&
        (bytes[3].toInt() and 255) == 0xa3) return true // EBML / WebM
    if (bytes.size >= 4 && (bytes[0].toInt() and 255) == 255) {
        val b = bytes[1].toInt() and 255
        if ((b and 0xf6) == 0xf0) return true // AAC ADTS
        if ((b and 0xe0) == 0xe0 && ((b shr 3) and 3) != 1 &&
            ((b shr 1) and 3) != 0) return true // MPEG audio
    }
    return false
}

/**
 * A provider ID alone is not proof of the server/account that produced audio.
 * Store a digest of the locally controlled configuration and an opaque source
 * generation instead. Existing cache bytes are never stamped retroactively.
 */
internal fun sourcePlaybackCacheScope(config: SourceConfig?, owner: ProviderKey): String? =
    sourcePlaybackCacheFingerprint(config, owner)

internal fun currentSourcePlaybackCacheScope(owner: ProviderKey): String? =
    sourcePlaybackCacheScope(SourceRegistry.config(owner.instanceId), owner)

internal fun sourceCacheScopeMatches(stored: String?, current: String?): Boolean =
    !stored.isNullOrBlank() && stored == current

/** Recognize an opaque, provider-scoped HTTP byte cache key, never a URL. */
internal fun isScopedSourcePhysicalCacheKey(key: String?): Boolean =
    key != null && Regex("^source-segment:(addon|custom_module):[^:]{1,128}:[0-9a-f]{64}$")
        .matches(key)

/** Cache key must match the exact owner as well as its persisted metadata. */
internal fun scopedPhysicalKeyBelongsTo(key: String, owner: ProviderKey): Boolean =
    isScopedSourcePhysicalCacheKey(key) &&
        key.startsWith("source-segment:" + owner.providerId + ":" + owner.instanceId + ":")

/** A logical MediaItem can address a verified HTTP cache bucket by opaque key only. */
internal fun scopedOfflineSourceCacheKey(
    scheme: String?,
    authority: String?,
    requestKey: String?,
): String? = requestKey?.takeIf {
    scheme == "bitchord" && authority == "source" &&
        isScopedSourcePhysicalCacheKey(it)
}

/**
 * A progressive root is never allowed to share a cache bucket with another
 * logical recording or variant, even when the resolved URL and headers happen
 * to be identical. This identity is hashed by providerSegmentCacheKey.
 */
internal fun scopedProgressiveRootRequestKey(
    requestKey: String?,
    logicalPartition: String,
): String {
    require(logicalPartition.isNotBlank()) { "Source logical recording key is missing" }
    return "progressive-root:" + logicalPartition.length + ":" + logicalPartition +
        ":" + requestKey.orEmpty()
}

/**
 * Media3 bypasses the HTTP datasource entirely on an HLS/DASH segment cache
 * hit. The per-media-item ticket is mandatory even when every byte is local.
 * One provider's revocation must not affect an authorized sibling session.
 */
internal fun admitAdaptiveSegmentCacheRead(
    lease: ProviderPlaybackByteLease?,
): ProviderPlaybackByteLease =
    lease?.takeIf(ProviderPlaybackByteLease::allowed)
        ?: throw IOException("Adaptive segment cache requires current provider authorization")

/**
 * Adaptive segment requests are HTTP URLs, not logical `bitchord://watch`
 * tracks. Their `v=` query parameters must never become the whole cache key:
 * that would collide across unrelated CDNs, addons and media items.
 *
 * Hash the full URL (including any short-lived signature) and Media3 request
 * key, and namespace it by provider instance. Persist no URL tokens in
 * plaintext cache metadata. Retain byte-range identity within each URL.
 */
internal fun providerAdaptiveSegmentCacheKey(
    owner: ProviderKey,
    url: String,
    requestKey: String? = null,
    headers: Map<String, String> = emptyMap(),
    sourceScope: String? = null,
): String {
    // Partition even identical URLs/Authorization headers across local source
    // endpoint/account generations, without writing credentials to the disk.
    val scopedRequest = sourceScope?.let { "source-realm:$it:" + requestKey.orEmpty() }
        ?: requestKey
    return providerSegmentCacheKey(owner, url, scopedRequest, headers)
}

/**
 * A manifest from a remote addon/module is untrusted. The initial logical
 * bitchord://source URI is resolved under its canonical provider gate; every
 * subsequently referenced resource must remain HTTP(S). Otherwise a malicious
 * HLS/DASH playlist could cause DefaultDataSource to open local file://,
 * content:// or SMB assets on the user's device on behalf of the provider.
 *
 * Normal local/MediaStore playback is outside this per-source factory.
 */
internal fun requireAdaptiveSourceResourceScheme(
    scheme: String?,
    authority: String?,
) {
    if (scheme == "http" || scheme == "https") return
    if (scheme == "bitchord" && authority == "source") return
    throw IOException("Unsupported scheme in remote provider media item")
}
