package com.music.bitchord.data.canvas

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Provider-independent destination of a still image or motion clip. */
enum class VisualSurface { ARTIST, ALBUM, NOW_PLAYING }

/**
 * STREAM_ONLY retains the existing bounded playback cache and Coil still art.
 * SAVE_ON_WIFI opts into keeping complete clips persistently on unmetered Wi-Fi.
 * STILL_ONLY replaces motion with the existing still artwork.
 */
enum class VisualRetentionMode { STREAM_ONLY, SAVE_ON_WIFI, STILL_ONLY }

/** A null network state is offline/unknown; never start an automatic download. */
fun shouldSaveVisualMotion(
    mode: VisualRetentionMode,
    surface: VisualSurface,
    allowedSurfaces: Set<VisualSurface>,
    metered: Boolean?,
): Boolean = mode == VisualRetentionMode.SAVE_ON_WIFI &&
    surface in allowedSurfaces && metered == false

fun shouldShowVisualMotion(
    mode: VisualRetentionMode,
    animatedEnabled: Boolean,
    metered: Boolean?,
    savedOffline: Boolean,
    cellularAllowed: Boolean,
): Boolean = animatedEnabled && mode != VisualRetentionMode.STILL_ONLY &&
    (savedOffline || metered == false || (metered == true && cellularAllowed))

/** Saved offline quotas are distinct from the transient Coil and Media3
 * caches. Defaults preserve the size used by existing installations. */
const val DEFAULT_SAVED_STILLS_BYTES = 256L * 1024 * 1024
const val DEFAULT_SAVED_MOTION_BYTES = 400L * 1024 * 1024
const val MIN_SAVED_STILLS_BYTES = 64L * 1024 * 1024
const val MAX_SAVED_STILLS_BYTES = 1024L * 1024 * 1024
const val MIN_SAVED_MOTION_BYTES = 128L * 1024 * 1024
const val MAX_SAVED_MOTION_BYTES = 2L * 1024 * 1024 * 1024

fun normalizedVisualStillCacheBytes(requested: Long): Long =
    if (requested == 0L) DEFAULT_SAVED_STILLS_BYTES
    else requested.coerceIn(MIN_SAVED_STILLS_BYTES, MAX_SAVED_STILLS_BYTES)

fun normalizedVisualMotionCacheBytes(requested: Long): Long =
    if (requested == 0L) DEFAULT_SAVED_MOTION_BYTES
    else requested.coerceIn(MIN_SAVED_MOTION_BYTES, MAX_SAVED_MOTION_BYTES)

/** No hostname, query, token, user information or file path in the disk key. */
fun visualMediaCacheKey(surface: VisualSurface, identity: String): String {
    val normalized = identity.trim().lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")
    val raw = "visual-v1|${surface.name}|$normalized"
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Do not fetch local files or private server URLs from untrusted metadata. */
fun isSafeVisualMediaUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (host == "localhost" || host.endsWith(".localhost") ||
        host == "0.0.0.0" || host == "::1" || host.startsWith("127.") ||
        host.startsWith("10.") || host.startsWith("192.168.") ||
        host.startsWith("169.254.") || host.startsWith("172.") ||
        host.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) ||
        host.startsWith("[") || host.contains(":")
    ) return false
    return uri.rawFragment == null && host.contains('.') && uri.port in -1..65535
}
