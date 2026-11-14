package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.net.URI
import java.util.Locale

/**
 * A successful CDN bounds probe proves that a larger image was available at
 * probe time, not that a later complete Coil download will always succeed.
 * Reconstruct only the exact original 100px thumbnail path from the *same*
 * Apple image asset; never switch editions, storefronts or archive sources.
 *
 * This is error recovery, not a source of unverified high-resolution URLs.
 */
fun appleVerifiedCdnCoverFallbackUrl(artworkUrl: String?): String? {
    if (artworkUrl.isNullOrBlank() || artworkUrl.length > 2048) return null
    val uri = try { URI(artworkUrl) } catch (_: Exception) { return null }
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (uri.scheme != "https" ||
        !Regex("""(?:is|a)\d+(?:-ssl)?\.mzstatic\.com""").matches(host) ||
        uri.userInfo != null || uri.port != -1 ||
        uri.rawQuery != null || uri.rawFragment != null ||
        uri.rawPath.isNullOrBlank() || uri.rawPath.contains('%')
    ) return null
    val suffix = Regex(
        """([/.])(?:1024x1024|720x720|600x600)(bb|-\d{2,3})\.(jpg|jpeg|png|webp)$""",
        RegexOption.IGNORE_CASE,
    ).find(uri.rawPath) ?: return null
    val originalSize = "${suffix.groupValues[1]}100x100" +
        "${suffix.groupValues[2]}.${suffix.groupValues[3]}"
    return artworkUrl.removeSuffix(suffix.value) + originalSize
}

/**
 * Small, source-safe Coil recovery for public Apple covers. On a full-image
 * failure, retry the asset's iTunes 100px URL exactly once. A secondary
 * failure ends normally. No CAA fallback, title search or extra MB requests.
 */
@Composable
fun ResilientAppleCover(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    val original = remember(model) { appleVerifiedCdnCoverFallbackUrl(model) }
    var retryOriginal by remember(model) { mutableStateOf(false) }
    AsyncImage(
        model = if (retryOriginal) original ?: model else model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = { state ->
            if (state is AsyncImagePainter.State.Error &&
                !retryOriginal && original != null
            ) retryOriginal = true
            onState?.invoke(state)
        },
    )
}
