package com.music.bitchord.acquisition.a0

/**
 * Bounded within a SINGLE user-initiated Soulseek discovery session.
 * Keeps canonical artist/title for screening; variants only change wire text.
 * Maximum two fallback requests, no reconnect/login/retry loop or transfer.
 */
internal class A0SearchRecoveryPlan(original:String,album:String?=null,releaseWide:Boolean=false) {
    private val originalQuery=original
    // Share the strict native-CJK + explicit Latin-title rule with rank and
    // release prefilter. An edition descriptor is not an alternate title.
    private val latinTitleAlias=A0CjkTitleAliases.latinAlias(
        original.split(Regex("\\s+[-–—]\\s+"),limit=2).getOrNull(1).orEmpty())

    // A generic title (e.g. "Normal") can match many unrelated releases.
    // Search the artist + canonical album + title before broad title-only.
    // This changes discovery text only: the original identity and all A3
    // admission gates are unchanged.
    private val albumQualified=runCatching {
        val originalNormalized=A0SearchQuery.normalize(original)
        val parts=original.split(Regex("\\s+[-–—]\\s+"),limit=2)
        if(parts.size!=2 || parts.any { it.isBlank() })return@runCatching null
        // A short title must not be searched by itself, but adding both
        // canonical artist and album still produces a bounded specific query.
        // This matters for two-syllable Korean songs and "Go"-type titles.
        val title=latinTitleAlias ?: A0SearchQuery.titleOnlyFallback(original)
            ?: A0SearchQuery.normalize(parts[1]).takeIf { it.length>=2 }
            ?:return@runCatching null
        val albumNormalized=A0SearchQuery.normalize(album.orEmpty())
        val originalTokens=originalNormalized.lowercase().split(' ')
        val albumTokens=albumNormalized.lowercase().split(' ')
        val titleTokens=title.lowercase().split(' ')
        if(albumTokens.all { it in originalTokens } ||
            albumTokens.all { it in titleTokens })return@runCatching null
        val artist=parts[0]
        A0SearchQuery.normalize("$artist $albumNormalized $title")
    }.getOrNull()
    // Read-only album + artist query can discover many independently eligible
    // sibling slots in a release, rather than 18s of per-track searches.
    // Never used for an isolated single-track manual search.
    private val albumWide=if(releaseWide)runCatching {
        val parts=original.split(Regex("\\s+[-–—]\\s+"),limit=2)
        if(parts.size!=2 || parts.any { it.isBlank() })return@runCatching null
        // Album-wide discovery uses artist+album, not a naked short title.
        // Validate the canonical title but do not require that the title
        // itself be safe for an unqualified title-only fallback.
        A0SearchQuery.normalize(parts[1])
        val artist=parts[0].substringBefore('(').trim()
        val normalizedAlbum=A0SearchQuery.normalize(album.orEmpty())
        if(artist.isBlank())return@runCatching null
        A0SearchQuery.normalize("$artist $normalizedAlbum")
    }.getOrNull() else null

    // Prefer exact artist+album+title before a broad title-only lookup
    // when canonical album context exists. A real bilingual Latin title
    // alias and an equivalent explicit edition spelling retain priority.
    // Both are discovery text only: the canonical track and A3 gates remain.
    private val variants=run {
        // Use the extracted Latin title in the actual two outbound slots,
        // rather than merely when composing the optional album-specific text.
        val title=latinTitleAlias ?: A0SearchQuery.titleOnlyFallback(original)
        val generic=title==null || title.length<8 || title.split(' ').size<2
        // Never throw on a malformed imported canonical title: discovery
        // refuses that slot before network I/O; planning is fail-closed too.
        val normal=runCatching {
            A0SearchQuery.variants(original).drop(1)
        }.getOrDefault(emptyList())
        // A peer advertising "Demo Version" need not answer "Demo Ver";
        // prefer the equivalent fully qualified song query over an overly
        // broad title-only fallback. The two-search cap is unchanged.
        val editionVariant=A0SearchQuery.editionSpellingVariant(original)
        // The physical 48-track smoke on 2026-11-04 found zero valid
        // responses to 18 album-qualified and 7 edition-equivalent fallback
        // searches, whereas 13 title-only/alias searches returned 164 new
        // offers. For DESCRIPTIVE multiword titles, search that useful
        // discovery text first. Short/generic titles still use the specific
        // album qualifier first, avoiding uncontrolled broad peer searches.
        // All ranking/artist/edition/quality gates keep the original target.
        // Album-wide queries retain their dedicated sibling-discovery role.
        val ordered=when {
            albumWide!=null && generic ->
                listOfNotNull(albumWide,albumQualified,editionVariant,title)+normal
            albumWide!=null ->
                listOfNotNull(albumWide,title,editionVariant,albumQualified)+normal
            generic ->
                listOfNotNull(albumQualified,editionVariant)+normal
            else ->
                listOfNotNull(title,editionVariant,albumQualified)+normal
        }
        // Keep the ranked candidates, not merely the first two raw choices.
        // An album-wide query sent by a sibling must not consume a fallback.
        ordered.filter { it.length>=3 }.distinct()
    }
    /** Enum-only search-kind diagnostics. Never emit wire text or identifiers. */
    internal enum class VariantKind {
        NONE, ALBUM_WIDE, ALBUM_QUALIFIED, EDITION_EQUIVALENT,
        TITLE_ONLY_OR_ALIAS, NORMALIZED_VARIANT, OTHER,
    }
    internal fun variantKind(wireText:String):VariantKind = when {
        albumWide!=null && wireText==albumWide -> VariantKind.ALBUM_WIDE
        albumQualified!=null && wireText==albumQualified ->
            VariantKind.ALBUM_QUALIFIED
        A0SearchQuery.editionSpellingVariant(originalQuery)==wireText ->
            VariantKind.EDITION_EQUIVALENT
        (latinTitleAlias ?: A0SearchQuery.titleOnlyFallback(originalQuery))==wireText ->
            VariantKind.TITLE_ONLY_OR_ALIAS
        wireText in runCatching { A0SearchQuery.variants(originalQuery).drop(1) }
            .getOrDefault(emptyList()) -> VariantKind.NORMALIZED_VARIANT
        else -> VariantKind.OTHER
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
