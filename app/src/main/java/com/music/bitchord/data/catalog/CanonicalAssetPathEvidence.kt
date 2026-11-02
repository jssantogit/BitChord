package com.music.bitchord.data.catalog

/**
 * A source-owned FILE path, not a playable URI. Do not infer a directory from
 * a content:// URI or another source's asset. Any unsupported path stays UNKNOWN.
 * Text comparison itself remains the same for local and remote providers.
 */
internal fun canonicalAssetFilePath(raw: String?): String? {
    val path = raw?.trim()?.replace('\\', '/') ?: return null
    if (path.isEmpty() || path.length > 4096 ||
        path.contains("://") || path.any { it.isISOControl() } ||
        path.endsWith('/')) return null
    return path.replace(Regex("/{2,}"), "/")
}

internal fun canonicalAssetFolderPath(raw: String?): String? {
    val path = canonicalAssetFilePath(raw) ?: return null
    val slash = path.lastIndexOf('/')
    return when {
        slash < 0 -> null
        slash == 0 -> "/"
        else -> path.substring(0, slash)
    }
}
