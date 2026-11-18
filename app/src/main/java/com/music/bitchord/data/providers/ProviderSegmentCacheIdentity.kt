package com.music.bitchord.data.providers

import java.security.MessageDigest

/**
 * Keys adaptive HTTP segments by the exact provider instance and entire
 * signed URL, so two providers (or two different renditions) cannot read
 * each other's cached bytes. Credentials and URL signatures are never placed
 * in plaintext cache keys. HTTP Range is encoded by the cache span position,
 * not by a second URL identity.
 */
internal fun providerSegmentCacheKey(
    owner: ProviderKey,
    url: String,
    requestKey: String? = null,
    headers: Map<String, String> = emptyMap(),
): String {
    // Include the identity of actual request headers *inside the digest*.
    // Signed URLs can be stable across account rotation with the account
    // selected only by Authorization. Reusing one cache bucket would leak
    // the previous account's bytes even though both tickets are valid.
    // Header names are case-insensitive; canonicalize and sort for stability.
    val fingerprintInput = buildString {
        append(url)
        append('\u0000')
        append(requestKey.orEmpty())
        headers.entries.map { it.key.lowercase(java.util.Locale.ROOT) to it.value }
            .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
            .forEach { (name, value) ->
                append('\u0000')
                append(name.length).append(':').append(name)
                append(value.length).append(':').append(value)
            }
    }
    val input = fingerprintInput.toByteArray(Charsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(input).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    input.fill(0)
    return "source-segment:${owner.providerId}:${owner.instanceId}:$hash"
}
