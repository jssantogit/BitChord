package com.music.bitchord.acquisition.a0

/**
 * A bounded alternative SONG title supplied explicitly by the canonical
 * metadata, e.g. "상남자 (Boy In Luv)" or "夜に駆ける (Racing Into The Night)".
 *
 * A parenthesized edition, collaboration or decorative label is never a song
 * alias. No transliteration, language translation, untrusted peer inference
 * or fuzzy Han conversion. A returned alias is merely a *candidate* spelling;
 * album, independent artist credit, quality and A3 gates remain mandatory.
 */
internal object A0CjkTitleAliases {
    private val parenthetical=Regex("^\\s*(.+?)\\s*\\(([^()]+)\\)\\s*$")
    private val descriptors=Regex(
        "^(?:live(?: (?:version|recording|performance|edit|mix|at .+|in .+))?" +
            "|remaster(?:ed)?(?: [0-9]{2,4})?|[0-9]{2,4} remaster(?:ed)?" +
            "|radio edit|extended mix|original mix|acoustic version" +
            "|instrumental version|demo version|clean version|explicit version" +
            "|(?:.+ )?remix)$",
        RegexOption.IGNORE_CASE)
    private val collaboration=Regex(
        "(?:^|\\s)(?:feat|ft|featuring|with|vs)(?:\\s|$)",
        RegexOption.IGNORE_CASE)
    // A canonical title carrying a distinct artist/edition description may
    // not be reduced to the Latin parenthetical: that would erase intent.
    private val unsafeBaseDescriptor=Regex(
        "(?:^|\\s)(?:feat\\.?|ft\\.?|featuring|with|vs\\.?|" +
            "live|remix|remastered?|acoustic|instrumental|demo|cover|" +
            "karaoke)(?:\\s|$)",RegexOption.IGNORE_CASE)

    private fun containsCjk(value:String):Boolean {
        var i=0
        while(i<value.length) {
            val cp=Character.codePointAt(value,i)
            when(Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL -> return true
                else -> Unit
            }
            i+=Character.charCount(cp)
        }
        return false
    }

    /** Only an explicit, multiword ASCII alternative after native CJK text. */
    fun latinAlias(canonicalTitle:String):String?=runCatching {
        val canonical=A0CjkOrthography.widthOnlyNfc(canonicalTitle)
        val match=parenthetical.matchEntire(canonical) ?: return@runCatching null
        if(!containsCjk(match.groupValues[1]) ||
            unsafeBaseDescriptor.containsMatchIn(match.groupValues[1]))
            return@runCatching null
        val alias=A0SearchQuery.normalize(match.groupValues[2])
        if(alias.length !in 8..80 || alias.split(' ').size<2 ||
            !alias.all { it.code in 32..126 } ||
            descriptors.matches(alias) || collaboration.containsMatchIn(alias))
            return@runCatching null
        alias
    }.getOrNull()
}
