package com.music.bitchord.acquisition.a0

/**
 * Bounded within a SINGLE user-initiated Soulseek discovery session.
 * Keeps canonical artist/title for screening; variants only change wire text.
 * Maximum two fallback requests, no reconnect/login/retry loop or transfer.
 */
internal class A0SearchRecoveryPlan(original:String,album:String?=null,releaseWide:Boolean=false) {
    // A bilingual title may carry a native-script name and a widely used
    // Latin title in trailing parentheses. Search the Latin alternative
    // as discovery only; the canonical selection gate is unchanged.
    private val latinTitleAlias=runCatching {
        val title=original.split(Regex("\\s+[-–—]\\s+"),limit=2)
            .getOrNull(1) ?: return@runCatching null
        if(title.none { it.code>127 })return@runCatching null
        val ending=Regex("\\(([^()]+)\\)\\s*$").find(title)
            ?:return@runCatching null
        val normalized=A0SearchQuery.normalize(ending.groupValues[1])
        if(normalized.length<8 || normalized.split(' ').size<2 ||
            !normalized.all { it.code in 32..126 })return@runCatching null
        normalized
    }.getOrNull()

    // A generic title (e.g. "Normal") can match many unrelated releases.
    // Search the artist + canonical album + title before broad title-only.
    // This changes discovery text only: the original identity and all A3
    // admission gates are unchanged.
    private val albumQualified=runCatching {
        val originalNormalized=A0SearchQuery.normalize(original)
        val title=latinTitleAlias ?: A0SearchQuery.titleOnlyFallback(original) ?: return@runCatching null
        val albumNormalized=A0SearchQuery.normalize(album.orEmpty())
        val originalTokens=originalNormalized.lowercase().split(' ')
        val albumTokens=albumNormalized.lowercase().split(' ')
        val titleTokens=title.lowercase().split(' ')
        if(albumTokens.all { it in originalTokens } ||
            albumTokens.all { it in titleTokens })return@runCatching null
        val artist=original.split(Regex("\\s+[-–—]\\s+"),limit=2).first()
        A0SearchQuery.normalize("$artist $albumNormalized $title")
    }.getOrNull()
    // Read-only album + artist query can discover many independently eligible
    // sibling slots in a release, rather than 18s of per-track searches.
    // Never used for an isolated single-track manual search.
    private val albumWide=if(releaseWide)runCatching {
        val artist=original.split(Regex("\\s+[-–—]\\s+"),limit=2).first()
            .substringBefore('(').trim()
        val normalizedAlbum=A0SearchQuery.normalize(album.orEmpty())
        if(artist.isBlank() || A0SearchQuery.titleOnlyFallback(original)==null)
            return@runCatching null
        A0SearchQuery.normalize("$artist $normalizedAlbum")
    }.getOrNull() else null

    // Avoid overconstraining descriptive title searches by adding a
    // compilation album. Generic one-word titles still prefer the album.
    // The original canonical track and quality gates are never changed.
    private val variants=run {
        // Use the extracted Latin title in the actual two outbound slots,
        // rather than merely when composing the optional album-specific text.
        val title=latinTitleAlias ?: A0SearchQuery.titleOnlyFallback(original)
        val generic=title==null || title.length<8 || title.split(' ').size<2
        val normal=A0SearchQuery.variants(original).drop(1)
        val ordered=when {
            albumWide!=null && generic -> listOfNotNull(albumWide,albumQualified,title)+normal
            albumWide!=null -> listOfNotNull(albumWide,title,albumQualified)+normal
            generic -> listOfNotNull(albumQualified)+normal
            else -> listOfNotNull(title,albumQualified)+normal
        }
        // Keep the ranked candidates, not merely the first two raw choices.
        // An album-wide query sent by a sibling must not consume a fallback.
        ordered.filter { it.length>=3 }.distinct()
    }
    /** Search-only key; a release query should be issued once per batch. */
    internal val releaseWideQuery:String? get()=albumWide
    val authorizesTransfer:Boolean get()=false

    fun next(
        elapsedMillis:Long,
        hasRelevantCandidate:Boolean,
        alreadySent:Set<String>,
        missingSiblings:Boolean=false,
        excludedQueries:Set<String> = emptySet(),
    ):String? {
        if(elapsedMillis<6000 || elapsedMillis>15000 ||
            alreadySent.size>=3)return null
        // An eligible first track does not prove that its 47 siblings
        // are present. Allow only the *one* album-wide discovery variant
        // in that case, not redundant searches for the already-ready title.
        if(hasRelevantCandidate)
            return if(missingSiblings)
                albumWide?.takeIf { it !in alreadySent && it !in excludedQueries }
                else null
        return variants.firstOrNull { it !in alreadySent && it !in excludedQueries }
    }

    override fun toString()="A0SearchRecoveryPlan(redacted)"
}
