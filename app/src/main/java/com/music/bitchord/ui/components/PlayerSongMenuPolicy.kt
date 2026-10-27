package com.music.bitchord.ui.components

/** Public-facing player actions remain intentionally small. Playback tools and
 * diagnostics keep working internally without occupying the per-song menu. */
internal enum class PlayerMenuExtra {
    REVERT_ORIGINAL,
    UPGRADE_QUALITY,
    CONVERT_VIDEO,
    SLEEP_TIMER,
    COPY_LOG,
    LYRICS_OFFSET,
}

internal object PlayerSongMenuPolicy {
    val visibleExtras: Set<PlayerMenuExtra> = setOf(PlayerMenuExtra.REVERT_ORIGINAL)
    fun offers(extra: PlayerMenuExtra): Boolean = extra in visibleExtras
}
