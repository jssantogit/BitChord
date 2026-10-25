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
}
