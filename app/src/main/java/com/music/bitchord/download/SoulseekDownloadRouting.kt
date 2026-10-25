package com.music.bitchord.download

import java.text.Normalizer
import java.util.Locale

/**
 * Converts a user-selected Song to a *suggested* Soulseek query.
 * A generic display category such as "Música" is not an artist credit.
 * Search always stays editable and never initiates transfer by itself.
 */
internal object SoulseekDownloadRouting {
    sealed interface Decision {
        data class ManualSearch(val query: String) : Decision
        data object BulkNotSupported : Decision
        data object Unavailable : Decision
    }

    private val unknownArtistLabels = setOf(
        "musica", "music", "song", "songs", "track", "faixa",
        "artista desconhecido", "unknown artist", "unknown",
        "various artists", "varios artistas", "unavailable", "desconhecido"
    )

    private fun text(value:String):String =
        value.trim().filterNot { it.isISOControl() }.replace(Regex("\\s+")," ")

    private fun credit(value:String):String? {
        val cleaned=text(value)
        val key=Normalizer.normalize(cleaned,Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"),"")
            .lowercase(Locale.ROOT)
        return cleaned.takeIf { it.isNotBlank() && key !in unknownArtistLabels }
    }

    /** A weak optional duration hint; never sent in a Soulseek query. */
    fun durationSeconds(display:String?):Int? {
        val components=display?.trim()?.split(':') ?: return null
        if (components.size !in 2..3 || components.any {
                it.isEmpty() || it.length>3 || it.any(Char::isLetter)
            })return null
        val numbers=components.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it<0 } || numbers.last()>59 ||
            (numbers.size==3 && numbers[1]>59))return null
        val result=numbers.fold(0L) {acc,value -> acc*60L+value }
        return result.toInt().takeIf { result in 15L..7200L }
    }

    fun route(
        artist:String, title:String, count:Int, pilotAvailable:Boolean,
        creditedArtists:List<String> = emptyList()
    ):Decision {
        if (count!=1) return Decision.BulkNotSupported
        if (!pilotAvailable) return Decision.Unavailable
        val name=text(title)
        if (name.isBlank()) return Decision.Unavailable
        val reliableArtist=creditedArtists.firstNotNullOfOrNull(::credit) ?: credit(artist)
        val shortTitle=name.take(120)
        val query=if(reliableArtist==null) shortTitle else {
            val boundedTitle=shortTitle.take(60)
            val shortArtist=reliableArtist.take(120-boundedTitle.length-3)
            "$shortArtist - $boundedTitle"
        }
        return Decision.ManualSearch(query)
    }

    /**
     * Read-only bridge for future #178 catalogue-aware ranking. Manual search
     * never mints Recording/Release MBIDs, even with album/artist text.
     * Existing route() and the actual transfer authorization are unchanged.
     */
    class PreparedManualSearch(
        val query: String,
        val snapshot: com.music.bitchord.acquisition.a0.A0CanonicalAcquisitionIntent,
    ) {
        override fun toString(): String = "PreparedManualSearch(redacted)"
    }

    fun prepareManual(
        artist: String, title: String, count: Int, pilotAvailable: Boolean,
        creditedArtists: List<String> = emptyList(),
        requestedAlbum: String? = null,
        expectedDurationSeconds: Int? = null,
        quality: com.music.bitchord.acquisition.a0.A0CanonicalAcquisitionIntent.QualityProfileSnapshot =
            com.music.bitchord.acquisition.a0.A0CanonicalAcquisitionIntent.QualityProfileSnapshot(),
    ): PreparedManualSearch? {
        val routed = route(artist, title, count, pilotAvailable, creditedArtists)
        if (routed !is Decision.ManualSearch) return null
        val confirmedDisplayCredit = creditedArtists.firstNotNullOfOrNull(::credit) ?: credit(artist)
        val snapshot = com.music.bitchord.acquisition.a0.A0CanonicalAcquisitionIntent.manual(
            title = text(title).take(120),
            artistCredits = listOfNotNull(confirmedDisplayCredit),
            requestedAlbum = requestedAlbum?.let(::text),
            expectedDurationSeconds = expectedDurationSeconds,
            quality = quality,
        )
        return PreparedManualSearch(routed.query, snapshot)
    }

}
