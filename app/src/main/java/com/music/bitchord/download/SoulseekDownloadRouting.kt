package com.music.bitchord.download

/**
 * A download tap is a request for a manual Soulseek file search, not authority to
 * fetch bytes. This contract is shared by track, album and playlist actions.
 */
internal object SoulseekDownloadRouting {
    sealed interface Decision {
        data class ManualSearch(val query: String) : Decision
        data object BulkNotSupported : Decision
        data object Unavailable : Decision
    }

    fun route(artist: String, title: String, count: Int, pilotAvailable: Boolean): Decision {
        if (count != 1) return Decision.BulkNotSupported
        if (!pilotAvailable) return Decision.Unavailable
        val safeArtist = artist.trim().filterNot { it.isISOControl() }.replace(Regex("\\s+"), " ")
        val safeTitle = title.trim().filterNot { it.isISOControl() }.replace(Regex("\\s+"), " ")
        if (safeArtist.isBlank() || safeTitle.isBlank()) return Decision.Unavailable
        // Reserve room for the title even if a provider supplies a huge artist string.
        val shortTitle = safeTitle.take(60)
        val shortArtist = safeArtist.take(120 - shortTitle.length - 3)
        val query = shortArtist + " - " + shortTitle
        return Decision.ManualSearch(query)
    }
}
