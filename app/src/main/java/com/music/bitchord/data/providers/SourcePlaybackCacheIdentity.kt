package com.music.bitchord.data.providers

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import java.security.MessageDigest

/** A source cache fingerprint is an ownership assertion, never an access grant. */
internal fun sourcePlaybackCacheFingerprint(config: SourceConfig?, owner: ProviderKey): String? {
    if (config == null || config.id != owner.instanceId ||
        config.playbackCacheEpoch.isBlank() || config.baseUrl.isBlank()) return null
    if (!when (config.kind) {
        SourceKind.ADDON -> owner.providerId == "addon"
        SourceKind.CUSTOM_MODULE -> owner.providerId == "custom_module"
        else -> false
    }) return null
    val input = listOf(
        "source-cache-v2", owner.providerId, owner.instanceId,
        config.kind.name, config.baseUrl, config.playbackCacheEpoch,
    ).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    } finally { input.fill(0) }
}
