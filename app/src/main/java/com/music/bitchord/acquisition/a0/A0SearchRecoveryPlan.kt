package com.music.bitchord.acquisition.a0

/**
 * Bounded within a SINGLE user-initiated Soulseek discovery session.
 * Keeps canonical artist/title for screening; variants only change wire text.
 * Maximum two fallback requests, no reconnect/login/retry loop or transfer.
 */
internal class A0SearchRecoveryPlan(original:String,album:String?=null) {
    // A generic title (e.g. "Normal") can match many unrelated releases.
    // Search the artist + canonical album + title before broad title-only.
    // This changes discovery text only: the original identity and all A3
    // admission gates are unchanged.
    private val albumQualified=runCatching {
        val originalNormalized=A0SearchQuery.normalize(original)
        val title=A0SearchQuery.titleOnlyFallback(original) ?: return@runCatching null
        val albumNormalized=A0SearchQuery.normalize(album.orEmpty())
        val originalTokens=originalNormalized.lowercase().split(' ')
        val albumTokens=albumNormalized.lowercase().split(' ')
        val titleTokens=title.lowercase().split(' ')
        if(albumTokens.all { it in originalTokens } ||
            albumTokens.all { it in titleTokens })return@runCatching null
        val artist=original.split(Regex("\\s+[-–—]\\s+"),limit=2).first()
        A0SearchQuery.normalize("$artist $albumNormalized $title")
    }.getOrNull()
    // Avoid overconstraining descriptive title searches by adding a
    // compilation album. Generic one-word titles still prefer the album.
    // The original canonical track and quality gates are never changed.
    private val variants=run {
        val title=A0SearchQuery.titleOnlyFallback(original)
        val generic=title==null || title.length<8 || title.split(' ').size<2
        val normal=A0SearchQuery.variants(original).drop(1)
        val ordered=if(generic)listOfNotNull(albumQualified)+normal
            else listOfNotNull(title,albumQualified)+normal
        ordered.filter { it.length>=3 }.distinct().take(2)
    }
    val authorizesTransfer:Boolean get()=false

    fun next(
        elapsedMillis:Long,
        hasRelevantCandidate:Boolean,
        alreadySent:Set<String>,
    ):String? {
        if(elapsedMillis<6000 || elapsedMillis>15000 ||
            hasRelevantCandidate || alreadySent.size>=3)return null
        return variants.firstOrNull { it !in alreadySent }
    }

    override fun toString()="A0SearchRecoveryPlan(redacted)"
}
