package com.music.bitchord.download

/**
 * Acquisition-owned destination choice for a future persistent Soulseek import.
 *
 * A0's observed test still deletes scratch bytes. This contract does not publish
 * downloaded data, change the existing YouTube download setting, or claim a SAF
 * grant. The publisher must independently verify persistable read/write grants
 * when opening the selected URI, and fail closed if the provider disappears.
 */
internal object A1DownloadDestinationPolicy {
    /** Same canonical folder already used by DownloadStore and local scanning. */
    const val DEFAULT_RELATIVE_PATH = "Music/BitChord/"

    /** Future filing modes must be explicit opt-ins; never infer an album from a filename. */
    enum class Layout { FLAT }
    val layout: Layout = Layout.FLAT

    sealed interface Target {
        data class SharedMusic(val relativePath: String) : Target
        data class GrantedTree(val treeUri: String) : Target
    }

    sealed interface Resolution {
        data class Ready(val target: Target) : Resolution
        data object NeedsReselection : Resolution
    }

    /**
     * Null means "never configured" and uses the public Music default.
     * A non-null but invalid persisted selection is NOT silently redirected to
     * Music, which would write private user-selected content to the wrong root.
     *
     * This only rejects plainly impossible URI forms. It does not grant access,
     * resolve a provider, validate ownership, or authorize a write.
     */
    fun resolve(persistedTreeUri: String?): Resolution {
        if (persistedTreeUri == null) {
            return Resolution.Ready(Target.SharedMusic(DEFAULT_RELATIVE_PATH))
        }
        if (persistedTreeUri.length !in 18..2048 ||
            !persistedTreeUri.startsWith("content://") ||
            persistedTreeUri.any { it.isWhitespace() || it.code <= 0x1f || it.code == 0x7f } ||
            '#' in persistedTreeUri || '?' in persistedTreeUri
        ) return Resolution.NeedsReselection
        val remainder = persistedTreeUri.removePrefix("content://")
        val authority = remainder.substringBefore('/')
        val path = remainder.substringAfter('/', missingDelimiterValue = "")
        val treeId = path.substringAfter("tree/", missingDelimiterValue = "")
        if (authority.isBlank() || authority.any {
                !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-'
            } || !path.startsWith("tree/") || treeId.isBlank() ||
            treeId.startsWith('/') || ".." in treeId
        ) return Resolution.NeedsReselection
        return Resolution.Ready(Target.GrantedTree(persistedTreeUri))
    }
}
